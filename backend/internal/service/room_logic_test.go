package service

import (
	"testing"

	"github.com/acc1111/aircraft-war-hitsz/backend/internal/entity"
	pb "github.com/acc1111/aircraft-war-hitsz/backend/proto"
)

func TestEnemyScore(t *testing.T) {
	if got := EnemyScore(pb.EnemyType_ENEMY_TYPE_MOB); got != 10 {
		t.Fatalf("mob score = %d, want 10", got)
	}
	if got := EnemyScore(pb.EnemyType_ENEMY_TYPE_ELITE); got != 20 {
		t.Fatalf("elite score = %d, want 20", got)
	}
	if got := EnemyScore(pb.EnemyType_ENEMY_TYPE_BOSS); got != 50 {
		t.Fatalf("boss score = %d, want 50", got)
	}
}

func TestNormalizeRoomDifficulty(t *testing.T) {
	if got := NormalizeRoomDifficulty(pb.RoomDifficulty_ROOM_DIFFICULTY_UNSPECIFIED); got != pb.RoomDifficulty_ROOM_DIFFICULTY_NORMAL {
		t.Fatalf("unspecified difficulty = %v, want NORMAL", got)
	}
	if got := NormalizeRoomDifficulty(pb.RoomDifficulty_ROOM_DIFFICULTY_HARD); got != pb.RoomDifficulty_ROOM_DIFFICULTY_HARD {
		t.Fatalf("hard difficulty = %v, want HARD", got)
	}
}

func TestFinalizeResultAndBuildRoomResult(t *testing.T) {
	players := map[string]*entity.PlayerState{
		"alice": {Score: 50, FinishReason: pb.PlayerFinishReason_PLAYER_FINISH_REASON_NORMAL},
		"bob":   {Score: 20, FinishReason: pb.PlayerFinishReason_PLAYER_FINISH_REASON_DISCONNECTED},
	}
	result := FinalizeResult("903064", []string{"alice", "bob"}, players, 123)
	if result.WinnerUsername != "alice" {
		t.Fatalf("winner = %q, want alice", result.WinnerUsername)
	}
	view := BuildRoomResult(result, "bob")
	if view.GetSelfResult() != pb.GameResultType_GAME_RESULT_LOSE {
		t.Fatalf("bob self result = %v, want LOSE", view.GetSelfResult())
	}
	if view.GetPlayerAFinishReason() != pb.PlayerFinishReason_PLAYER_FINISH_REASON_NORMAL {
		t.Fatalf("player A finish reason = %v, want NORMAL", view.GetPlayerAFinishReason())
	}
	if view.GetPlayerBFinishReason() != pb.PlayerFinishReason_PLAYER_FINISH_REASON_DISCONNECTED {
		t.Fatalf("player B finish reason = %v, want DISCONNECTED", view.GetPlayerBFinishReason())
	}
}
