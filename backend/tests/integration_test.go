package tests

import (
	"bytes"
	"database/sql"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	_ "modernc.org/sqlite"
	"google.golang.org/protobuf/proto"

	"github.com/acc1111/aircraft-war-hitsz/backend/internal/app"
	pb "github.com/acc1111/aircraft-war-hitsz/backend/proto"
)

func TestRoomLifecycleScoreResultAndLeaderboard(t *testing.T) {
	server := newFileDBTestServer(t, "full-lifecycle.sqlite")
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice"}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	roomID := createResp.Room.RoomId
	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob"}, &pb.JoinRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "alice"}, &pb.ReadyRoomResponse{})
	readyResp := postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "bob"}, &pb.ReadyRoomResponse{}).(*pb.ReadyRoomResponse)
	if got := readyResp.Room.Status; got != pb.RoomStatus_ROOM_STATUS_READY {
		t.Fatalf("room status after ready = %v, want READY", got)
	}
	startResp := postProto(t, server.URL, "/rooms/start", &pb.StartGameRequest{RoomId: roomID, Username: "alice"}, &pb.StartGameResponse{}).(*pb.StartGameResponse)
	if !startResp.Started {
		t.Fatal("expected room to start")
	}

	aliceWS := openWS(t, server.URL, roomID, "alice")
	defer aliceWS.Close()
	bobWS := openWS(t, server.URL, roomID, "bob")
	defer bobWS.Close()

	writeProtoWS(t, aliceWS, wrapHeartbeat(roomID, "alice", 1))
	writeProtoWS(t, bobWS, wrapHeartbeat(roomID, "bob", 1))
	writeProtoWS(t, aliceWS, wrapDefeat(roomID, "alice", pb.EnemyType_ENEMY_TYPE_BOSS, "a-1"))

	broadcastA := readScoreBroadcast(t, aliceWS)
	broadcastB := readScoreBroadcast(t, bobWS)
	assertScore(t, broadcastA, "alice", 50, false, pb.PlayerFinishReason_PLAYER_FINISH_REASON_UNSPECIFIED)
	assertScore(t, broadcastB, "alice", 50, false, pb.PlayerFinishReason_PLAYER_FINISH_REASON_UNSPECIFIED)

	writeProtoWS(t, aliceWS, wrapGameOver(roomID, "alice", 50, "done"))
	broadcastAfterA := readScoreBroadcast(t, bobWS)
	assertScore(t, broadcastAfterA, "alice", 50, true, pb.PlayerFinishReason_PLAYER_FINISH_REASON_NORMAL)

	writeProtoWS(t, bobWS, wrapDefeat(roomID, "bob", pb.EnemyType_ENEMY_TYPE_ELITE, "b-1"))
	readScoreBroadcast(t, aliceWS)
	readScoreBroadcast(t, bobWS)

	writeProtoWS(t, bobWS, wrapGameOver(roomID, "bob", 20, "done"))
	finishedA := readGameFinished(t, aliceWS)
	finishedB := readGameFinished(t, bobWS)
	if finishedA.Result.WinnerUsername != "alice" || finishedB.Result.WinnerUsername != "alice" {
		t.Fatalf("winner mismatch: %q %q", finishedA.Result.WinnerUsername, finishedB.Result.WinnerUsername)
	}

	resultResp := postProto(t, server.URL, "/rooms/result", &pb.GetRoomResultRequest{RoomId: roomID, Username: "alice"}, &pb.GetRoomResultResponse{}).(*pb.GetRoomResultResponse)
	if got := resultResp.Result.SelfResult; got != pb.GameResultType_GAME_RESULT_WIN {
		t.Fatalf("alice self result = %v, want WIN", got)
	}
	if got := resultResp.Result.PlayerAFinishReason; got != pb.PlayerFinishReason_PLAYER_FINISH_REASON_NORMAL {
		t.Fatalf("player a finish reason = %v, want NORMAL", got)
	}
	if got := resultResp.Result.PlayerBFinishReason; got != pb.PlayerFinishReason_PLAYER_FINISH_REASON_NORMAL {
		t.Fatalf("player b finish reason = %v, want NORMAL", got)
	}
	leaderboard := postProto(t, server.URL, "/leaderboard", &pb.GetLeaderboardRequest{Limit: 10}, &pb.GetLeaderboardResponse{}).(*pb.GetLeaderboardResponse)
	if len(leaderboard.Entries) != 2 {
		t.Fatalf("leaderboard size = %d, want 2", len(leaderboard.Entries))
	}
	if leaderboard.Entries[0].Username != "alice" || leaderboard.Entries[0].BestScore != 50 || leaderboard.Entries[0].WinCount != 1 || leaderboard.Entries[0].GameCount != 1 {
		t.Fatalf("unexpected top leaderboard entry: %+v", leaderboard.Entries[0])
	}

	// 直接校验 SQLite 持久化结果，确保完整对局结束后房间结果和排行榜都已落库。
	db := openSQLiteForAssert(t, server.dbPath)
	defer db.Close()

	var storedWinner string
	var playerAScore, playerBScore int32
	if err := db.QueryRow(`SELECT winner_username, player_a_score, player_b_score FROM room_results WHERE room_id = ?`, roomID).
		Scan(&storedWinner, &playerAScore, &playerBScore); err != nil {
		t.Fatalf("query room_results: %v", err)
	}
	if storedWinner != "alice" || playerAScore != 50 || playerBScore != 20 {
		t.Fatalf("unexpected stored room result: winner=%q a=%d b=%d", storedWinner, playerAScore, playerBScore)
	}

	entries := loadLeaderboardRows(t, db)
	if len(entries) != 2 {
		t.Fatalf("stored leaderboard size = %d, want 2", len(entries))
	}
	if entries[0].Username != "alice" || entries[0].BestScore != 50 || entries[0].WinCount != 1 || entries[0].GameCount != 1 {
		t.Fatalf("unexpected stored top leaderboard entry: %+v", entries[0])
	}
}

func TestDisconnectFreezesScoreAndStateRecovery(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice"}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	roomID := createResp.Room.RoomId
	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob"}, &pb.JoinRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "alice"}, &pb.ReadyRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "bob"}, &pb.ReadyRoomResponse{})
	postProto(t, server.URL, "/rooms/start", &pb.StartGameRequest{RoomId: roomID, Username: "alice"}, &pb.StartGameResponse{})

	aliceWS := openWS(t, server.URL, roomID, "alice")
	defer aliceWS.Close()
	bobWS := openWS(t, server.URL, roomID, "bob")
	defer bobWS.Close()

	writeProtoWS(t, aliceWS, wrapDefeat(roomID, "alice", pb.EnemyType_ENEMY_TYPE_MOB, "a-1"))
	readScoreBroadcast(t, aliceWS)
	readScoreBroadcast(t, bobWS)

	writeProtoWS(t, bobWS, wrapHeartbeat(roomID, "bob", 1))
	time.Sleep(60 * time.Millisecond)
	writeProtoWS(t, bobWS, wrapHeartbeat(roomID, "bob", 2))
	time.Sleep(90 * time.Millisecond)

	disconnectBroadcast := readScoreBroadcast(t, bobWS)
	assertScore(t, disconnectBroadcast, "alice", 10, true, pb.PlayerFinishReason_PLAYER_FINISH_REASON_DISCONNECTED)

	writeProtoWS(t, aliceWS, wrapDefeat(roomID, "alice", pb.EnemyType_ENEMY_TYPE_BOSS, "a-2"))
	writeProtoWS(t, bobWS, wrapDefeat(roomID, "bob", pb.EnemyType_ENEMY_TYPE_BOSS, "b-1"))
	continueBroadcast := readScoreBroadcast(t, bobWS)
	assertScore(t, continueBroadcast, "alice", 10, true, pb.PlayerFinishReason_PLAYER_FINISH_REASON_DISCONNECTED)
	assertScore(t, continueBroadcast, "bob", 50, false, pb.PlayerFinishReason_PLAYER_FINISH_REASON_UNSPECIFIED)

	stateResp := postProto(t, server.URL, "/rooms/state", &pb.GetRoomStateRequest{RoomId: roomID, Username: "alice"}, &pb.GetRoomStateResponse{}).(*pb.GetRoomStateResponse)
	if stateResp.RoomFinished {
		t.Fatal("room should not be finished while bob is still playing")
	}
	if stateResp.Result != nil {
		t.Fatal("room state should not include final result before room finished")
	}
	assertScore(t, &pb.ScoreBroadcast{Scores: stateResp.Scores}, "alice", 10, true, pb.PlayerFinishReason_PLAYER_FINISH_REASON_DISCONNECTED)

	writeProtoWS(t, bobWS, wrapGameOver(roomID, "bob", 50, "done"))
	finished := readGameFinished(t, bobWS)
	if finished.Result.WinnerUsername != "bob" {
		t.Fatalf("winner = %q, want bob", finished.Result.WinnerUsername)
	}
	resultResp := postProto(t, server.URL, "/rooms/result", &pb.GetRoomResultRequest{RoomId: roomID, Username: "alice"}, &pb.GetRoomResultResponse{}).(*pb.GetRoomResultResponse)
	if got := resultResp.Result.PlayerAFinishReason; got == pb.PlayerFinishReason_PLAYER_FINISH_REASON_UNSPECIFIED {
		t.Fatal("expected disconnect finish reason to be recorded")
	}
	finalState := postProto(t, server.URL, "/rooms/state", &pb.GetRoomStateRequest{RoomId: roomID, Username: "alice"}, &pb.GetRoomStateResponse{}).(*pb.GetRoomStateResponse)
	if !finalState.RoomFinished {
		t.Fatal("room should be finished after both players end")
	}
	if finalState.Result == nil {
		t.Fatal("finished room state should include final result")
	}
	leaderboard := postProto(t, server.URL, "/leaderboard", &pb.GetLeaderboardRequest{Limit: 10}, &pb.GetLeaderboardResponse{}).(*pb.GetLeaderboardResponse)
	if leaderboard.Entries[0].Username != "bob" || leaderboard.Entries[1].Username != "alice" {
		t.Fatalf("unexpected leaderboard order after disconnect: %+v", leaderboard.Entries)
	}
}

func TestStartGameBroadcastsPlayingStatusToOtherPlayer(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice", Difficulty: pb.RoomDifficulty_ROOM_DIFFICULTY_HARD}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	roomID := createResp.Room.RoomId
	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob"}, &pb.JoinRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "alice"}, &pb.ReadyRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "bob"}, &pb.ReadyRoomResponse{})

	aliceWS := openWS(t, server.URL, roomID, "alice")
	defer aliceWS.Close()
	bobWS := openWS(t, server.URL, roomID, "bob")
	defer bobWS.Close()

	startResp := postProto(t, server.URL, "/rooms/start", &pb.StartGameRequest{RoomId: roomID, Username: "alice"}, &pb.StartGameResponse{}).(*pb.StartGameResponse)
	if !startResp.Started {
		t.Fatal("expected room to start")
	}

	broadcast := readScoreBroadcast(t, bobWS)
	assertScoreStatus(t, broadcast, "alice", 0, pb.PlayerStatus_PLAYER_STATUS_PLAYING)
	assertScoreStatus(t, broadcast, "bob", 0, pb.PlayerStatus_PLAYER_STATUS_PLAYING)
	stateResp := postProto(t, server.URL, "/rooms/state", &pb.GetRoomStateRequest{RoomId: roomID, Username: "bob"}, &pb.GetRoomStateResponse{}).(*pb.GetRoomStateResponse)
	if stateResp.Room.GetStatus() != pb.RoomStatus_ROOM_STATUS_PLAYING {
		t.Fatalf("room status = %v, want PLAYING", stateResp.Room.GetStatus())
	}
	if stateResp.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_HARD {
		t.Fatalf("room difficulty = %v, want HARD", stateResp.Room.GetDifficulty())
	}
	_ = aliceWS
}

func TestRoomDifficultyInitializationAndBroadcast(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{
		Username:   "alice",
		AvatarId:   "pilot-alpha",
		Difficulty: pb.RoomDifficulty_ROOM_DIFFICULTY_HARD,
	}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	if createResp.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_HARD {
		t.Fatalf("created room difficulty = %v, want HARD", createResp.Room.GetDifficulty())
	}
	roomID := createResp.Room.RoomId
	aliceWS := openWS(t, server.URL, roomID, "alice")
	defer aliceWS.Close()

	initial := readRoomStateBroadcast(t, aliceWS)
	if initial.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_HARD {
		t.Fatalf("initial room broadcast difficulty = %v, want HARD", initial.Room.GetDifficulty())
	}

	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob", AvatarId: "pilot-bravo"}, &pb.JoinRoomResponse{})
	joined := readRoomStateBroadcast(t, aliceWS)
	if joined.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_HARD {
		t.Fatalf("joined room difficulty = %v, want HARD", joined.Room.GetDifficulty())
	}

	bobWS := openWS(t, server.URL, roomID, "bob")
	defer bobWS.Close()
	bobInitial := readRoomStateBroadcast(t, bobWS)
	if bobInitial.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_HARD {
		t.Fatalf("bob initial room difficulty = %v, want HARD", bobInitial.Room.GetDifficulty())
	}

	updateResp := postProto(t, server.URL, "/rooms/difficulty", &pb.UpdateRoomDifficultyRequest{
		RoomId:     roomID,
		Username:   "alice",
		Difficulty: pb.RoomDifficulty_ROOM_DIFFICULTY_EASY,
	}, &pb.UpdateRoomDifficultyResponse{}).(*pb.UpdateRoomDifficultyResponse)
	if updateResp.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_EASY {
		t.Fatalf("updated room difficulty = %v, want EASY", updateResp.Room.GetDifficulty())
	}

	hostBroadcast := readRoomStateBroadcast(t, aliceWS)
	guestBroadcast := readRoomStateBroadcast(t, bobWS)
	if hostBroadcast.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_EASY {
		t.Fatalf("host broadcast difficulty = %v, want EASY", hostBroadcast.Room.GetDifficulty())
	}
	if guestBroadcast.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_EASY {
		t.Fatalf("guest broadcast difficulty = %v, want EASY", guestBroadcast.Room.GetDifficulty())
	}

	stateResp := postProto(t, server.URL, "/rooms/state", &pb.GetRoomStateRequest{RoomId: roomID, Username: "bob"}, &pb.GetRoomStateResponse{}).(*pb.GetRoomStateResponse)
	if stateResp.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_EASY {
		t.Fatalf("state room difficulty = %v, want EASY", stateResp.Room.GetDifficulty())
	}
}

func TestGuestCannotChangeRoomDifficulty(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice"}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	roomID := createResp.Room.RoomId
	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob"}, &pb.JoinRoomResponse{})

	resp, err := postProtoRaw(server.URL, "/rooms/difficulty", &pb.UpdateRoomDifficultyRequest{
		RoomId:     roomID,
		Username:   "bob",
		Difficulty: pb.RoomDifficulty_ROOM_DIFFICULTY_HARD,
	})
	if err != nil {
		t.Fatalf("post raw update difficulty: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("guest update status = %d, want %d", resp.StatusCode, http.StatusForbidden)
	}

	stateResp := postProto(t, server.URL, "/rooms/state", &pb.GetRoomStateRequest{RoomId: roomID, Username: "alice"}, &pb.GetRoomStateResponse{}).(*pb.GetRoomStateResponse)
	if stateResp.Room.GetDifficulty() != pb.RoomDifficulty_ROOM_DIFFICULTY_NORMAL {
		t.Fatalf("room difficulty after guest update = %v, want NORMAL", stateResp.Room.GetDifficulty())
	}
}

func TestCannotChangeRoomDifficultyAfterStart(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice"}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	roomID := createResp.Room.RoomId
	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob"}, &pb.JoinRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "alice"}, &pb.ReadyRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "bob"}, &pb.ReadyRoomResponse{})
	postProto(t, server.URL, "/rooms/start", &pb.StartGameRequest{RoomId: roomID, Username: "alice"}, &pb.StartGameResponse{})

	resp, err := postProtoRaw(server.URL, "/rooms/difficulty", &pb.UpdateRoomDifficultyRequest{
		RoomId:     roomID,
		Username:   "alice",
		Difficulty: pb.RoomDifficulty_ROOM_DIFFICULTY_HARD,
	})
	if err != nil {
		t.Fatalf("post raw update difficulty after start: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("update after start status = %d, want %d", resp.StatusCode, http.StatusConflict)
	}
}

func TestFinalResultBroadcastUnblocksFinishedClient(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice"}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	roomID := createResp.Room.RoomId
	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob"}, &pb.JoinRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "alice"}, &pb.ReadyRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "bob"}, &pb.ReadyRoomResponse{})
	postProto(t, server.URL, "/rooms/start", &pb.StartGameRequest{RoomId: roomID, Username: "alice"}, &pb.StartGameResponse{})

	aliceWS := openWS(t, server.URL, roomID, "alice")
	defer aliceWS.Close()
	bobWS := openWS(t, server.URL, roomID, "bob")
	defer bobWS.Close()

	writeProtoWS(t, aliceWS, wrapDefeat(roomID, "alice", pb.EnemyType_ENEMY_TYPE_BOSS, "a-1"))
	readScoreBroadcast(t, aliceWS)
	readScoreBroadcast(t, bobWS)

	writeProtoWS(t, aliceWS, wrapGameOver(roomID, "alice", 50, "done"))
	readRoomStateOrScoreThenRoomState(t, aliceWS)
	readRoomStateOrScoreThenRoomState(t, bobWS)

	writeProtoWS(t, bobWS, wrapDefeat(roomID, "bob", pb.EnemyType_ENEMY_TYPE_MOB, "b-1"))
	readScoreBroadcast(t, aliceWS)
	readScoreBroadcast(t, bobWS)
	writeProtoWS(t, bobWS, wrapGameOver(roomID, "bob", 10, "done"))

	finalState := readRoomStateOrScoreThenRoomState(t, aliceWS)
	if !finalState.GetRoomFinished() {
		t.Fatal("alice final room state should be finished")
	}
	if finalState.GetResult() == nil {
		t.Fatal("alice final room state should include result")
	}
	if finalState.GetResult().GetSelfResult() != pb.GameResultType_GAME_RESULT_WIN {
		t.Fatalf("alice self result = %v, want WIN", finalState.GetResult().GetSelfResult())
	}
	_ = readGameFinished(t, bobWS)
}

func TestRoomStateBroadcastOnJoinAndReady(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice", AvatarId: "pilot-alpha"}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	roomID := createResp.Room.RoomId
	aliceWS := openWS(t, server.URL, roomID, "alice")
	defer aliceWS.Close()

	initial := readRoomStateBroadcast(t, aliceWS)
	assertRoomPlayer(t, initial.Room, "alice", pb.PlayerStatus_PLAYER_STATUS_JOINED, true, "pilot-alpha")
	if initial.Room.GetStatus() != pb.RoomStatus_ROOM_STATUS_WAITING {
		t.Fatalf("initial room status = %v, want WAITING", initial.Room.GetStatus())
	}

	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob", AvatarId: "pilot-bravo"}, &pb.JoinRoomResponse{})
	joined := readRoomStateBroadcast(t, aliceWS)
	if joined.Room.GetStatus() != pb.RoomStatus_ROOM_STATUS_FULL {
		t.Fatalf("joined room status = %v, want FULL", joined.Room.GetStatus())
	}
	assertRoomPlayer(t, joined.Room, "alice", pb.PlayerStatus_PLAYER_STATUS_JOINED, true, "pilot-alpha")
	assertRoomPlayer(t, joined.Room, "bob", pb.PlayerStatus_PLAYER_STATUS_JOINED, false, "pilot-bravo")

	bobWS := openWS(t, server.URL, roomID, "bob")
	defer bobWS.Close()
	_ = readRoomStateBroadcast(t, bobWS)

	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "alice"}, &pb.ReadyRoomResponse{})
	aliceReadyHost := readRoomStateBroadcast(t, aliceWS)
	aliceReadyGuest := readRoomStateBroadcast(t, bobWS)
	assertRoomPlayer(t, aliceReadyHost.Room, "alice", pb.PlayerStatus_PLAYER_STATUS_READY, true, "pilot-alpha")
	assertRoomPlayer(t, aliceReadyGuest.Room, "alice", pb.PlayerStatus_PLAYER_STATUS_READY, true, "pilot-alpha")
	if aliceReadyHost.Room.GetStatus() != pb.RoomStatus_ROOM_STATUS_FULL {
		t.Fatalf("room status after first ready = %v, want FULL", aliceReadyHost.Room.GetStatus())
	}

	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "bob"}, &pb.ReadyRoomResponse{})
	readyHost := readRoomStateBroadcast(t, aliceWS)
	readyGuest := readRoomStateBroadcast(t, bobWS)
	if readyHost.Room.GetStatus() != pb.RoomStatus_ROOM_STATUS_READY {
		t.Fatalf("host room status after both ready = %v, want READY", readyHost.Room.GetStatus())
	}
	if readyGuest.Room.GetStatus() != pb.RoomStatus_ROOM_STATUS_READY {
		t.Fatalf("guest room status after both ready = %v, want READY", readyGuest.Room.GetStatus())
	}
	assertRoomPlayer(t, readyHost.Room, "bob", pb.PlayerStatus_PLAYER_STATUS_READY, false, "pilot-bravo")
	assertRoomPlayer(t, readyGuest.Room, "bob", pb.PlayerStatus_PLAYER_STATUS_READY, false, "pilot-bravo")
}

func TestWebSocketSendsInitialRoomState(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice", AvatarId: "pilot-alpha"}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	roomID := createResp.Room.RoomId
	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob", AvatarId: "pilot-bravo"}, &pb.JoinRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "alice"}, &pb.ReadyRoomResponse{})

	bobWS := openWS(t, server.URL, roomID, "bob")
	defer bobWS.Close()

	snapshot := readRoomStateBroadcast(t, bobWS)
	if snapshot.Room.GetRoomId() != roomID {
		t.Fatalf("snapshot room id = %q, want %q", snapshot.Room.GetRoomId(), roomID)
	}
	if snapshot.Room.GetStatus() != pb.RoomStatus_ROOM_STATUS_FULL {
		t.Fatalf("snapshot room status = %v, want FULL", snapshot.Room.GetStatus())
	}
	if snapshot.RoomFinished {
		t.Fatal("snapshot should not mark room finished")
	}
	if snapshot.Result != nil {
		t.Fatal("snapshot result should be nil before room is finished")
	}
	assertRoomPlayer(t, snapshot.Room, "alice", pb.PlayerStatus_PLAYER_STATUS_READY, true, "pilot-alpha")
	assertRoomPlayer(t, snapshot.Room, "bob", pb.PlayerStatus_PLAYER_STATUS_JOINED, false, "pilot-bravo")
	assertScoreStatus(t, &pb.ScoreBroadcast{Scores: snapshot.Scores}, "alice", 0, pb.PlayerStatus_PLAYER_STATUS_READY)
	assertScoreStatus(t, &pb.ScoreBroadcast{Scores: snapshot.Scores}, "bob", 0, pb.PlayerStatus_PLAYER_STATUS_JOINED)
}

func TestLeaderboardPersistsAvatar(t *testing.T) {
	server := newFileDBTestServer(t, "leaderboard-avatar.sqlite")
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice", AvatarId: "pilot-alpha"}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	roomID := createResp.Room.RoomId
	postProto(t, server.URL, "/rooms/join", &pb.JoinRoomRequest{RoomId: roomID, Username: "bob", AvatarId: "pilot-bravo"}, &pb.JoinRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "alice"}, &pb.ReadyRoomResponse{})
	postProto(t, server.URL, "/rooms/ready", &pb.ReadyRoomRequest{RoomId: roomID, Username: "bob"}, &pb.ReadyRoomResponse{})
	postProto(t, server.URL, "/rooms/start", &pb.StartGameRequest{RoomId: roomID, Username: "alice"}, &pb.StartGameResponse{})

	aliceWS := openWS(t, server.URL, roomID, "alice")
	defer aliceWS.Close()
	bobWS := openWS(t, server.URL, roomID, "bob")
	defer bobWS.Close()
	_ = readRoomStateBroadcast(t, aliceWS)
	_ = readRoomStateBroadcast(t, bobWS)

	writeProtoWS(t, aliceWS, wrapDefeat(roomID, "alice", pb.EnemyType_ENEMY_TYPE_BOSS, "a-1"))
	readScoreBroadcast(t, aliceWS)
	readScoreBroadcast(t, bobWS)
	writeProtoWS(t, aliceWS, wrapGameOver(roomID, "alice", 50, "done"))
	_ = readRoomStateOrScoreThenRoomState(t, aliceWS)
	_ = readRoomStateOrScoreThenRoomState(t, bobWS)

	writeProtoWS(t, bobWS, wrapDefeat(roomID, "bob", pb.EnemyType_ENEMY_TYPE_ELITE, "b-1"))
	readScoreBroadcast(t, aliceWS)
	readScoreBroadcast(t, bobWS)
	writeProtoWS(t, bobWS, wrapGameOver(roomID, "bob", 20, "done"))
	_ = readRoomStateOrScoreThenRoomState(t, aliceWS)
	finished := readGameFinished(t, bobWS)
	if finished.Result.WinnerUsername != "alice" {
		t.Fatalf("winner = %q, want alice", finished.Result.WinnerUsername)
	}

	leaderboard := postProto(t, server.URL, "/leaderboard", &pb.GetLeaderboardRequest{Limit: 10}, &pb.GetLeaderboardResponse{}).(*pb.GetLeaderboardResponse)
	if len(leaderboard.Entries) != 2 {
		t.Fatalf("leaderboard size = %d, want 2", len(leaderboard.Entries))
	}
	assertLeaderboardAvatar(t, leaderboard.Entries, "alice", "pilot-alpha")
	assertLeaderboardAvatar(t, leaderboard.Entries, "bob", "pilot-bravo")

	db := openSQLiteForAssert(t, server.dbPath)
	defer db.Close()

	rows := loadLeaderboardRows(t, db)
	assertLeaderboardRowAvatar(t, rows, "alice", "pilot-alpha")
	assertLeaderboardRowAvatar(t, rows, "bob", "pilot-bravo")
}

func TestCreateRoomReturnsSixDigitNumericRoomID(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	createResp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "alice"}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
	if !regexp.MustCompile(`^\d{6}$`).MatchString(createResp.Room.GetRoomId()) {
		t.Fatalf("room id = %q, want six-digit numeric code", createResp.Room.GetRoomId())
	}
}

func TestConcurrentRoomCreationReturnsUniqueRoomIDs(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	const roomCount = 100
	ids := make(chan string, roomCount)
	var wg sync.WaitGroup
	for i := 0; i < roomCount; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			resp := postProto(t, server.URL, "/rooms/create", &pb.CreateRoomRequest{Username: "player-" + strings.ReplaceAll(time.Now().Add(time.Duration(index)*time.Nanosecond).Format("150405.000000000"), ".", "")}, &pb.CreateRoomResponse{}).(*pb.CreateRoomResponse)
			ids <- resp.Room.GetRoomId()
		}(i)
	}
	wg.Wait()
	close(ids)

	seen := make(map[string]struct{}, roomCount)
	for id := range ids {
		if !regexp.MustCompile(`^\d{6}$`).MatchString(id) {
			t.Fatalf("room id = %q, want six-digit numeric code", id)
		}
		if _, exists := seen[id]; exists {
			t.Fatalf("duplicate room id generated: %s", id)
		}
		seen[id] = struct{}{}
	}
}

type dbBackedServer struct {
	*httptest.Server
	dbPath string
}

func newTestServer(t *testing.T) *dbBackedServer {
	t.Helper()
	return newConfiguredTestServer(t, ":memory:")
}

func newFileDBTestServer(t *testing.T, fileName string) *dbBackedServer {
	t.Helper()
	dbPath := filepath.Join(t.TempDir(), fileName)
	// 显式删除旧数据库文件，避免重复运行时复用上一次残留数据。
	if err := os.Remove(dbPath); err != nil && !os.IsNotExist(err) {
		t.Fatalf("remove stale db: %v", err)
	}
	return newConfiguredTestServer(t, dbPath)
}

func newConfiguredTestServer(t *testing.T, dbPath string) *dbBackedServer {
	t.Helper()
	backend, err := app.NewServer(app.Config{
		DBPath:            dbPath,
		HeartbeatTimeout:  90 * time.Millisecond,
		HeartbeatRecheck:  30 * time.Millisecond,
		HeartbeatScanTick: 10 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("new server: %v", err)
	}
	httpServer := httptest.NewServer(backend.Handler())
	t.Cleanup(func() {
		httpServer.Close()
		_ = backend.Close()
	})
	return &dbBackedServer{Server: httpServer, dbPath: dbPath}
}

type leaderboardRow struct {
	Username  string
	BestScore int32
	WinCount  int32
	GameCount int32
	AvatarID  string
}

func openSQLiteForAssert(t *testing.T, dbPath string) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatalf("open sqlite db: %v", err)
	}
	return db
}

func loadLeaderboardRows(t *testing.T, db *sql.DB) []leaderboardRow {
	t.Helper()
	rows, err := db.Query(`SELECT username, best_score, win_count, game_count, avatar_id FROM leaderboard ORDER BY best_score DESC, updated_at ASC, username ASC`)
	if err != nil {
		t.Fatalf("query leaderboard: %v", err)
	}
	defer rows.Close()
	var entries []leaderboardRow
	for rows.Next() {
		var entry leaderboardRow
		if err := rows.Scan(&entry.Username, &entry.BestScore, &entry.WinCount, &entry.GameCount, &entry.AvatarID); err != nil {
			t.Fatalf("scan leaderboard row: %v", err)
		}
		entries = append(entries, entry)
	}
	return entries
}

func postProto(t *testing.T, baseURL, path string, req proto.Message, response proto.Message) proto.Message {
	t.Helper()
	payload, err := proto.Marshal(req)
	if err != nil {
		t.Fatalf("marshal request: %v", err)
	}
	httpResp, err := http.Post(baseURL+path, "application/x-protobuf", bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("post %s: %v", path, err)
	}
	defer httpResp.Body.Close()
	if httpResp.StatusCode != http.StatusOK {
		body := new(bytes.Buffer)
		_, _ = body.ReadFrom(httpResp.Body)
		t.Fatalf("post %s status=%d body=%s", path, httpResp.StatusCode, body.String())
	}
	body := new(bytes.Buffer)
	_, _ = body.ReadFrom(httpResp.Body)
	if err := proto.Unmarshal(body.Bytes(), response); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	return response
}

func postProtoRaw(baseURL, path string, req proto.Message) (*http.Response, error) {
	payload, err := proto.Marshal(req)
	if err != nil {
		return nil, err
	}
	return http.Post(baseURL+path, "application/x-protobuf", bytes.NewReader(payload))
}

func openWS(t *testing.T, baseURL, roomID, username string) *websocket.Conn {
	t.Helper()
	wsURL := "ws" + strings.TrimPrefix(baseURL, "http") + "/ws?room_id=" + url.QueryEscape(roomID) + "&username=" + url.QueryEscape(username)
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial ws: %v", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	return conn
}

func writeProtoWS(t *testing.T, conn *websocket.Conn, msg proto.Message) {
	t.Helper()
	payload, err := proto.Marshal(msg)
	if err != nil {
		t.Fatalf("marshal ws msg: %v", err)
	}
	if err := conn.WriteMessage(websocket.BinaryMessage, payload); err != nil {
		t.Fatalf("write ws msg: %v", err)
	}
}

func wrapHeartbeat(roomID, username string, sequence int64) *pb.WsMessage {
	return &pb.WsMessage{Payload: &pb.WsMessage_PlayerHeartbeatEvent{PlayerHeartbeatEvent: &pb.PlayerHeartbeatEvent{RoomId: roomID, Username: username, Sequence: sequence}}}
}

func wrapDefeat(roomID, username string, enemyType pb.EnemyType, eventID string) *pb.WsMessage {
	return &pb.WsMessage{Payload: &pb.WsMessage_PlayerDefeatEvent{PlayerDefeatEvent: &pb.PlayerDefeatEvent{RoomId: roomID, Username: username, EnemyType: enemyType, ClientEventId: eventID}}}
}

func wrapGameOver(roomID, username string, finalScore int32, reason string) *pb.WsMessage {
	return &pb.WsMessage{Payload: &pb.WsMessage_PlayerGameOverEvent{PlayerGameOverEvent: &pb.PlayerGameOverEvent{RoomId: roomID, Username: username, FinalScore: finalScore, Reason: reason}}}
}

func readScoreBroadcast(t *testing.T, conn *websocket.Conn) *pb.ScoreBroadcast {
	t.Helper()
	for {
		msgType, payload, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read score broadcast: %v", err)
		}
		if msgType != websocket.BinaryMessage {
			continue
		}
		frame := &pb.WsMessage{}
		if err := proto.Unmarshal(payload, frame); err != nil {
			t.Fatalf("unmarshal ws frame: %v", err)
		}
		msg, ok := frame.Payload.(*pb.WsMessage_ScoreBroadcast)
		if !ok {
			continue
		}
		return msg.ScoreBroadcast
	}
}

func readRoomStateBroadcast(t *testing.T, conn *websocket.Conn) *pb.RoomStateBroadcast {
	t.Helper()
	msgType, payload, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read room state broadcast: %v", err)
	}
	if msgType != websocket.BinaryMessage {
		t.Fatalf("message type = %d, want binary", msgType)
	}
	frame := &pb.WsMessage{}
	if err := proto.Unmarshal(payload, frame); err != nil {
		t.Fatalf("unmarshal ws frame: %v", err)
	}
	msg, ok := frame.Payload.(*pb.WsMessage_RoomStateBroadcast)
	if !ok {
		t.Fatalf("payload type = %T, want room state broadcast", frame.Payload)
	}
	return msg.RoomStateBroadcast
}

func readRoomStateOrScoreThenRoomState(t *testing.T, conn *websocket.Conn) *pb.RoomStateBroadcast {
	t.Helper()
	for {
		msgType, payload, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read room state broadcast: %v", err)
		}
		if msgType != websocket.BinaryMessage {
			continue
		}
		frame := &pb.WsMessage{}
		if err := proto.Unmarshal(payload, frame); err != nil {
			t.Fatalf("unmarshal ws frame: %v", err)
		}
		switch msg := frame.Payload.(type) {
		case *pb.WsMessage_RoomStateBroadcast:
			return msg.RoomStateBroadcast
		case *pb.WsMessage_ScoreBroadcast:
			continue
		case *pb.WsMessage_GameFinishedBroadcast:
			continue
		default:
			t.Fatalf("payload type = %T, want room state, score, or game finished broadcast", frame.Payload)
		}
	}
}

func readGameFinished(t *testing.T, conn *websocket.Conn) *pb.GameFinishedBroadcast {
	t.Helper()
	for {
		msgType, payload, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read game finished: %v", err)
		}
		if msgType != websocket.BinaryMessage {
			continue
		}
		frame := &pb.WsMessage{}
		if err := proto.Unmarshal(payload, frame); err != nil {
			t.Fatalf("unmarshal ws frame: %v", err)
		}
		switch msg := frame.Payload.(type) {
		case *pb.WsMessage_ScoreBroadcast:
			continue
		case *pb.WsMessage_RoomStateBroadcast:
			continue
		case *pb.WsMessage_GameFinishedBroadcast:
			return msg.GameFinishedBroadcast
		default:
			t.Fatalf("payload type = %T, want game finished", frame.Payload)
		}
	}
}

func assertScore(t *testing.T, broadcast *pb.ScoreBroadcast, username string, wantScore int32, wantFinished bool, wantReason pb.PlayerFinishReason) {
	t.Helper()
	for _, score := range broadcast.Scores {
		if score.Username != username {
			continue
		}
		if score.Score != wantScore || score.Finished != wantFinished || score.FinishReason != wantReason {
			t.Fatalf("score[%s] = %+v, want score=%d finished=%v reason=%v", username, score, wantScore, wantFinished, wantReason)
		}
		return
	}
	t.Fatalf("score for %s not found", username)
}

func assertScoreStatus(t *testing.T, broadcast *pb.ScoreBroadcast, username string, wantScore int32, wantStatus pb.PlayerStatus) {
	t.Helper()
	for _, score := range broadcast.Scores {
		if score.Username != username {
			continue
		}
		if score.Score != wantScore || score.Status != wantStatus {
			t.Fatalf("score[%s] = %+v, want score=%d status=%v", username, score, wantScore, wantStatus)
		}
		return
	}
	t.Fatalf("score for %s not found", username)
}

func assertRoomPlayer(t *testing.T, room *pb.Room, username string, wantStatus pb.PlayerStatus, wantHost bool, wantAvatarID string) {
	t.Helper()
	for _, player := range room.Players {
		if player.Username != username {
			continue
		}
		if player.Status != wantStatus || player.IsHost != wantHost || player.AvatarId != wantAvatarID {
			t.Fatalf("room player[%s] = %+v, want status=%v host=%v avatar=%q", username, player, wantStatus, wantHost, wantAvatarID)
		}
		return
	}
	t.Fatalf("room player %s not found", username)
}

func assertLeaderboardAvatar(t *testing.T, entries []*pb.LeaderboardEntry, username, wantAvatarID string) {
	t.Helper()
	for _, entry := range entries {
		if entry.Username != username {
			continue
		}
		if entry.AvatarId != wantAvatarID {
			t.Fatalf("leaderboard avatar for %s = %q, want %q", username, entry.AvatarId, wantAvatarID)
		}
		return
	}
	t.Fatalf("leaderboard entry for %s not found", username)
}

func assertLeaderboardRowAvatar(t *testing.T, rows []leaderboardRow, username, wantAvatarID string) {
	t.Helper()
	for _, row := range rows {
		if row.Username != username {
			continue
		}
		if row.AvatarID != wantAvatarID {
			t.Fatalf("stored leaderboard avatar for %s = %q, want %q", username, row.AvatarID, wantAvatarID)
		}
		return
	}
	t.Fatalf("stored leaderboard row for %s not found", username)
}
