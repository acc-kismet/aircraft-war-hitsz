package app

import (
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/acc1111/aircraft-war-hitsz/backend/internal/entity"
	"github.com/acc1111/aircraft-war-hitsz/backend/internal/repo"
	"github.com/acc1111/aircraft-war-hitsz/backend/internal/service"
	"github.com/gorilla/websocket"
	_ "modernc.org/sqlite"
	"google.golang.org/protobuf/proto"

	pb "github.com/acc1111/aircraft-war-hitsz/backend/proto"
)

type Config struct {
	DBPath            string
	HeartbeatTimeout  time.Duration
	HeartbeatRecheck  time.Duration
	HeartbeatScanTick time.Duration
	Now               func() time.Time
}

type Server struct {
	cfg      Config
	db       *sql.DB
	repo     *repo.SQLiteRepo
	handler  http.Handler
	upgrader websocket.Upgrader

	mu    sync.RWMutex
	rooms map[string]*roomState

	closeOnce sync.Once
	stopCh    chan struct{}
	doneCh    chan struct{}
	randMu    sync.Mutex
}

type roomState struct {
	room             *pb.Room
	playerOrder      []string
	players          map[string]*playerState
	conns            map[string]*clientConn
	result           *persistedResult
	winCountApplied  bool
}

type clientConn struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

type playerState = entity.PlayerState

type persistedResult = entity.PersistedResult

func NewServer(cfg Config) (*Server, error) {
	if cfg.DBPath == "" {
		cfg.DBPath = ":memory:"
	}
	if cfg.HeartbeatTimeout == 0 {
		cfg.HeartbeatTimeout = 9 * time.Second
	}
	if cfg.HeartbeatRecheck == 0 {
		cfg.HeartbeatRecheck = 3 * time.Second
	}
	if cfg.HeartbeatScanTick == 0 {
		cfg.HeartbeatScanTick = time.Second
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	db, err := sql.Open("sqlite", cfg.DBPath)
	if err != nil {
		return nil, err
	}
	server := &Server{
		cfg: cfg,
		db:  db,
		repo: repo.NewSQLiteRepo(db),
		upgrader: websocket.Upgrader{
			CheckOrigin: func(_ *http.Request) bool { return true },
		},
		rooms:  make(map[string]*roomState),
		stopCh: make(chan struct{}),
		doneCh: make(chan struct{}),
	}
	if err := server.repo.Init(); err != nil {
		_ = db.Close()
		return nil, err
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/rooms/create", server.handleCreateRoom)
	mux.HandleFunc("/rooms/join", server.handleJoinRoom)
	mux.HandleFunc("/rooms/ready", server.handleReadyRoom)
	mux.HandleFunc("/rooms/difficulty", server.handleUpdateRoomDifficulty)
	mux.HandleFunc("/rooms/start", server.handleStartGame)
	mux.HandleFunc("/rooms/result", server.handleGetRoomResult)
	mux.HandleFunc("/rooms/state", server.handleGetRoomState)
	mux.HandleFunc("/leaderboard", server.handleLeaderboard)
	mux.HandleFunc("/ws", server.handleWebSocket)
	server.handler = mux
	go server.monitorHeartbeats()
	return server, nil
}

func (s *Server) Handler() http.Handler { return s.handler }

func (s *Server) Close() error {
	s.closeOnce.Do(func() {
		close(s.stopCh)
		<-s.doneCh
		s.mu.Lock()
		for _, room := range s.rooms {
			for _, conn := range room.conns {
				_ = conn.conn.Close()
			}
		}
		s.mu.Unlock()
	})
	return s.db.Close()
}

func (s *Server) handleCreateRoom(w http.ResponseWriter, r *http.Request) {
	var req pb.CreateRoomRequest
	if err := s.decodeRequest(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if strings.TrimSpace(req.Username) == "" {
		http.Error(w, "username is required", http.StatusBadRequest)
		return
	}
	now := s.cfg.Now()
	player := &pb.Player{Username: req.Username, Status: pb.PlayerStatus_PLAYER_STATUS_JOINED, IsHost: true, AvatarId: req.AvatarId}
	difficulty := service.NormalizeRoomDifficulty(req.Difficulty)
	s.mu.Lock()
	roomID := s.newUniqueRoomIDLocked(now)
	room := &pb.Room{
		RoomId:      roomID,
		Status:      pb.RoomStatus_ROOM_STATUS_WAITING,
		Players:     []*pb.Player{proto.Clone(player).(*pb.Player)},
		Difficulty:  difficulty,
	}
	s.rooms[room.RoomId] = &roomState{
		room:        room,
		playerOrder: []string{req.Username},
		players: map[string]*playerState{
			req.Username: {Player: player, LastHeartbeat: now, DefeatEventIDs: make(map[string]struct{})},
		},
		conns: make(map[string]*clientConn),
	}
	s.mu.Unlock()
	s.writeProto(w, http.StatusOK, &pb.CreateRoomResponse{Room: cloneRoom(room), Self: proto.Clone(player).(*pb.Player)})
}

func (s *Server) handleUpdateRoomDifficulty(w http.ResponseWriter, r *http.Request) {
	var req pb.UpdateRoomDifficultyRequest
	if err := s.decodeRequest(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	room, player, err := s.lookupRoomPlayer(req.RoomId, req.Username)
	if err != nil {
		s.mu.Unlock()
		s.writeLookupError(w, err)
		return
	}
	if !player.Player.IsHost {
		s.mu.Unlock()
		http.Error(w, "only host can update difficulty", http.StatusForbidden)
		return
	}
	if room.room.Status == pb.RoomStatus_ROOM_STATUS_PLAYING || room.room.Status == pb.RoomStatus_ROOM_STATUS_FINISHED {
		s.mu.Unlock()
		http.Error(w, "room difficulty is locked", http.StatusConflict)
		return
	}
	room.room.Difficulty = service.NormalizeRoomDifficulty(req.Difficulty)
	responseRoom := cloneRoom(room.room)
	broadcasts := s.buildRoomStateBroadcastsForConnectionsLocked(room)
	connections := s.snapshotConnectionsLocked(room)
	s.mu.Unlock()
	go s.broadcastRoomStateBroadcasts(connections, broadcasts)
	s.writeProto(w, http.StatusOK, &pb.UpdateRoomDifficultyResponse{Room: responseRoom})
}

func (s *Server) handleJoinRoom(w http.ResponseWriter, r *http.Request) {
	var req pb.JoinRoomRequest
	if err := s.decodeRequest(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	room, ok := s.rooms[req.RoomId]
	if !ok {
		s.mu.Unlock()
		http.Error(w, "room not found", http.StatusNotFound)
		return
	}
	if room.room.Status == pb.RoomStatus_ROOM_STATUS_FINISHED {
		s.mu.Unlock()
		http.Error(w, "room finished", http.StatusGone)
		return
	}
	if len(room.room.Players) >= 2 && room.players[req.Username] == nil {
		s.mu.Unlock()
		http.Error(w, "room full", http.StatusConflict)
		return
	}
	if _, exists := room.players[req.Username]; exists {
		if req.AvatarId != "" {
			room.players[req.Username].Player.AvatarId = req.AvatarId
			updateRoomPlayer(room.room, room.players[req.Username].Player)
		}
		self := proto.Clone(room.players[req.Username].Player).(*pb.Player)
		s.mu.Unlock()
		s.writeProto(w, http.StatusOK, &pb.JoinRoomResponse{Room: cloneRoom(room.room), Self: self})
		return
	}
	player := &pb.Player{Username: req.Username, Status: pb.PlayerStatus_PLAYER_STATUS_JOINED, AvatarId: req.AvatarId}
	room.room.Players = append(room.room.Players, proto.Clone(player).(*pb.Player))
	room.room.Status = pb.RoomStatus_ROOM_STATUS_FULL
	room.playerOrder = append(room.playerOrder, req.Username)
	room.players[req.Username] = &playerState{Player: player, LastHeartbeat: s.cfg.Now(), DefeatEventIDs: make(map[string]struct{})}
	broadcast := s.buildRoomStateBroadcastLocked(room, "")
	connections := s.snapshotConnectionsLocked(room)
	responseRoom := cloneRoom(room.room)
	self := proto.Clone(player).(*pb.Player)
	s.mu.Unlock()
	go s.broadcastToConnections(connections, broadcast)
	s.writeProto(w, http.StatusOK, &pb.JoinRoomResponse{Room: responseRoom, Self: self})
}

func (s *Server) handleReadyRoom(w http.ResponseWriter, r *http.Request) {
	var req pb.ReadyRoomRequest
	if err := s.decodeRequest(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	room, player, err := s.lookupRoomPlayer(req.RoomId, req.Username)
	if err != nil {
		s.mu.Unlock()
		s.writeLookupError(w, err)
		return
	}
	if player.Player.Status == pb.PlayerStatus_PLAYER_STATUS_FINISHED {
		s.mu.Unlock()
		http.Error(w, "player finished", http.StatusGone)
		return
	}
	player.Player.Status = pb.PlayerStatus_PLAYER_STATUS_READY
	updateRoomPlayer(room.room, player.Player)
	if len(room.room.Players) == 2 && allPlayers(room, func(ps *playerState) bool {
		return ps.Player.Status == pb.PlayerStatus_PLAYER_STATUS_READY
	}) {
		room.room.Status = pb.RoomStatus_ROOM_STATUS_READY
	}
	broadcast := s.buildRoomStateBroadcastLocked(room, "")
	connections := s.snapshotConnectionsLocked(room)
	responseRoom := cloneRoom(room.room)
	s.mu.Unlock()
	go s.broadcastToConnections(connections, broadcast)
	s.writeProto(w, http.StatusOK, &pb.ReadyRoomResponse{Room: responseRoom})
}

func (s *Server) handleStartGame(w http.ResponseWriter, r *http.Request) {
	var req pb.StartGameRequest
	if err := s.decodeRequest(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	room, player, err := s.lookupRoomPlayer(req.RoomId, req.Username)
	if err != nil {
		s.mu.Unlock()
		s.writeLookupError(w, err)
		return
	}
	if !player.Player.IsHost {
		s.mu.Unlock()
		http.Error(w, "only host can start", http.StatusForbidden)
		return
	}
	if room.room.Status != pb.RoomStatus_ROOM_STATUS_READY {
		s.mu.Unlock()
		http.Error(w, "room not ready", http.StatusConflict)
		return
	}
	room.room.Status = pb.RoomStatus_ROOM_STATUS_PLAYING
	now := s.cfg.Now()
	for _, ps := range room.players {
		ps.Player.Status = pb.PlayerStatus_PLAYER_STATUS_PLAYING
		ps.LastHeartbeat = now
		updateRoomPlayer(room.room, ps.Player)
	}
	roomStateBroadcasts := s.buildRoomStateBroadcastsForConnectionsLocked(room)
	broadcast := &pb.ScoreBroadcast{RoomId: req.RoomId, Scores: roomScores(room), UpdatedAt: now.UnixMilli()}
	connections := s.snapshotConnectionsLocked(room)
	responseRoom := cloneRoom(room.room)
	s.mu.Unlock()
	go s.broadcastRoomStateBroadcasts(connections, roomStateBroadcasts)
	go s.broadcastToConnections(connections, broadcast)
	s.writeProto(w, http.StatusOK, &pb.StartGameResponse{Room: responseRoom, Started: true})
}

func (s *Server) handleGetRoomState(w http.ResponseWriter, r *http.Request) {
	var req pb.GetRoomStateRequest
	if err := s.decodeRequest(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	room, _, err := s.lookupRoomPlayer(req.RoomId, req.Username)
	if err != nil {
		s.writeLookupError(w, err)
		return
	}
	resp := &pb.GetRoomStateResponse{
		Room:         cloneRoom(room.room),
		Scores:       roomScores(room),
		RoomFinished: room.room.Status == pb.RoomStatus_ROOM_STATUS_FINISHED,
	}
	if room.result != nil {
		resp.Result = service.BuildRoomResult(room.result, req.Username)
	}
	s.writeProto(w, http.StatusOK, resp)
}

func (s *Server) handleGetRoomResult(w http.ResponseWriter, r *http.Request) {
	var req pb.GetRoomResultRequest
	if err := s.decodeRequest(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.mu.RLock()
	room, _, err := s.lookupRoomPlayer(req.RoomId, req.Username)
	if err == nil && room.result != nil {
		res := service.BuildRoomResult(room.result, req.Username)
		s.mu.RUnlock()
		s.writeProto(w, http.StatusOK, &pb.GetRoomResultResponse{Result: res})
		return
	}
	s.mu.RUnlock()

	row, err := s.repo.FindRoomResult(req.RoomId)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "room result not ready", http.StatusConflict)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	s.writeProto(w, http.StatusOK, &pb.GetRoomResultResponse{Result: service.BuildRoomResult(row, req.Username)})
}

func (s *Server) handleLeaderboard(w http.ResponseWriter, r *http.Request) {
	var req pb.GetLeaderboardRequest
	if err := s.decodeRequest(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	limit := req.Limit
	if limit <= 0 {
		limit = 20
	}
	offset := req.Offset
	entries, err := s.repo.ListLeaderboard(limit, offset)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	s.writeProto(w, http.StatusOK, &pb.GetLeaderboardResponse{Entries: entries})
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	roomID := r.URL.Query().Get("room_id")
	username := r.URL.Query().Get("username")
	s.mu.Lock()
	room, player, err := s.lookupRoomPlayer(roomID, username)
	if err != nil {
		s.mu.Unlock()
		s.writeLookupError(w, err)
		return
	}
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.mu.Unlock()
		return
	}
	oldConn := room.conns[username]
	room.conns[username] = &clientConn{conn: conn}
	player.LastHeartbeat = s.cfg.Now()
	broadcast := s.buildRoomStateBroadcastLocked(room, username)
	s.mu.Unlock()
	if oldConn != nil {
		_ = oldConn.conn.Close()
	}
	go s.broadcastToConnections([]*clientConn{{conn: conn}}, broadcast)
	go s.readLoop(roomID, username, conn)
}

func (s *Server) readLoop(roomID, username string, conn *websocket.Conn) {
	defer func() {
		s.mu.Lock()
		if room, ok := s.rooms[roomID]; ok && room.conns[username] != nil && room.conns[username].conn == conn {
			delete(room.conns, username)
		}
		s.mu.Unlock()
		_ = conn.Close()
	}()
	for {
		messageType, payload, err := conn.ReadMessage()
		if err != nil {
			return
		}
		if messageType != websocket.BinaryMessage {
			continue
		}
		if err := s.processClientMessage(roomID, username, payload); err != nil {
			log.Printf("ws message ignored room=%s user=%s err=%v", roomID, username, err)
		}
	}
}

func (s *Server) processClientMessage(roomID, username string, payload []byte) error {
	// WebSocket 统一只传 WsMessage，先解包再根据 oneof 分发具体事件。
	var frame pb.WsMessage
	if err := proto.Unmarshal(payload, &frame); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	room, player, err := s.lookupRoomPlayer(roomID, username)
	if err != nil {
		return err
	}
	switch msg := frame.Payload.(type) {
	case *pb.WsMessage_PlayerHeartbeatEvent:
		event := msg.PlayerHeartbeatEvent
		if event.RoomId != roomID || event.Username != username {
			return errors.New("heartbeat identity mismatch")
		}
		// 心跳只刷新服务端最后接收时间，供 9 秒超时 + 3 秒复检判断使用。
		player.LastHeartbeat = s.cfg.Now()
		return nil
	case *pb.WsMessage_PlayerDefeatEvent:
		event := msg.PlayerDefeatEvent
		if event.RoomId != roomID || event.Username != username {
			return errors.New("defeat identity mismatch")
		}
		if room.room.Status != pb.RoomStatus_ROOM_STATUS_PLAYING {
			return errors.New("room is not playing")
		}
		if player.Player.Status == pb.PlayerStatus_PLAYER_STATUS_FINISHED {
			return errors.New("player already finished")
		}
		// 击败事件也会刷新最后活跃时间，避免玩家持续作战时因漏心跳被误判掉线。
		player.LastHeartbeat = s.cfg.Now()
		if _, ok := player.DefeatEventIDs[event.ClientEventId]; ok {
			return nil
		}
		player.DefeatEventIDs[event.ClientEventId] = struct{}{}
		// 最终计分只以 enemy_type 为准，score_delta 不参与权威累计。
		player.Score += service.EnemyScore(event.EnemyType)
		broadcast := &pb.ScoreBroadcast{RoomId: roomID, Scores: roomScores(room), UpdatedAt: s.cfg.Now().UnixMilli()}
		go s.broadcastToConnections(s.snapshotConnectionsLocked(room), broadcast)
		return nil
	case *pb.WsMessage_PlayerGameOverEvent:
		event := msg.PlayerGameOverEvent
		if event.RoomId != roomID || event.Username != username {
			return errors.New("game over identity mismatch")
		}
		player.LastHeartbeat = s.cfg.Now()
		if player.Player.Status == pb.PlayerStatus_PLAYER_STATUS_FINISHED {
			return nil
		}
		// 主动结束只改变结束状态，最终分数仍以服务端当前累计值为准。
		if err := s.finishPlayerLocked(room, username, pb.PlayerFinishReason_PLAYER_FINISH_REASON_NORMAL); err != nil {
			return err
		}
		return nil
	default:
		return errors.New("unsupported client message")
	}
}

func (s *Server) finishPlayerLocked(room *roomState, username string, reason pb.PlayerFinishReason) error {
	player := room.players[username]
	// 一旦玩家被服务端标记结束，该玩家本局分数立即冻结，后续击败事件不再生效。
	player.Player.Status = pb.PlayerStatus_PLAYER_STATUS_FINISHED
	player.FinishReason = reason
	updateRoomPlayer(room.room, player.Player)
	if err := s.applyLeaderboardProgressLocked(player.Player, player.Score); err != nil {
		return err
	}
	roomStateBroadcasts := s.buildRoomStateBroadcastsForConnectionsLocked(room)
	broadcast := &pb.ScoreBroadcast{RoomId: room.room.RoomId, Scores: roomScores(room), UpdatedAt: s.cfg.Now().UnixMilli()}
	connections := s.snapshotConnectionsLocked(room)
	if allPlayers(room, func(ps *playerState) bool {
		return ps.Player.Status == pb.PlayerStatus_PLAYER_STATUS_FINISHED
	}) {
		// 只有双方都结束后，房间才进入最终完成态并广播整局结算。
		room.room.Status = pb.RoomStatus_ROOM_STATUS_FINISHED
		result := s.finalizeRoomLocked(room)
		roomStateBroadcasts = s.buildRoomStateBroadcastsForConnectionsLocked(room)
		go s.broadcastRoomStateBroadcasts(connections, roomStateBroadcasts)
		go s.broadcastToConnections(connections, broadcast)
		go s.broadcastToConnections(connections, &pb.GameFinishedBroadcast{RoomId: room.room.RoomId, Finished: true, Result: service.BuildRoomResult(result, "")})
		return nil
	}
	go s.broadcastRoomStateBroadcasts(connections, roomStateBroadcasts)
	go s.broadcastToConnections(connections, broadcast)
	return nil
}

func (s *Server) finalizeRoomLocked(room *roomState) *persistedResult {
	if room.result != nil {
		return room.result
	}
	result := service.FinalizeResult(room.room.RoomId, room.playerOrder, room.players, s.cfg.Now().UnixMilli())
	if !room.winCountApplied && result.WinnerUsername != "" {
		if err := s.applyWinnerWinCountLocked(result.WinnerUsername); err != nil {
			log.Printf("update winner win count failed: %v", err)
		} else {
			room.winCountApplied = true
		}
	}
	if err := s.persistResultLocked(result); err != nil {
		log.Printf("persist result failed: %v", err)
	}
	room.result = result
	return result
}

func (s *Server) persistResultLocked(result *persistedResult) error {
	return s.repo.SaveRoomResult(result)
}

func (s *Server) applyLeaderboardProgressLocked(player *pb.Player, score int32) error {
	return s.repo.UpsertLeaderboardProgress(player, score, s.cfg.Now().UnixMilli())
}

func (s *Server) applyWinnerWinCountLocked(username string) error {
	return s.repo.IncrementWinnerWinCount(username)
}

func (s *Server) monitorHeartbeats() {
	defer close(s.doneCh)
	ticker := time.NewTicker(s.cfg.HeartbeatScanTick)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopCh:
			return
		case <-ticker.C:
			s.scanHeartbeatTimeouts()
		}
	}
}

func (s *Server) scanHeartbeatTimeouts() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.cfg.Now()
	for _, room := range s.rooms {
		if room.room.Status != pb.RoomStatus_ROOM_STATUS_PLAYING {
			continue
		}
		for username, player := range room.players {
			if player.Player.Status == pb.PlayerStatus_PLAYER_STATUS_FINISHED {
				continue
			}
			elapsed := now.Sub(player.LastHeartbeat)
			// 协议要求 9 秒超时后再给 3 秒复检窗口，因此总阈值为 timeout + recheck。
			if elapsed < s.cfg.HeartbeatTimeout+s.cfg.HeartbeatRecheck {
				continue
			}
			log.Printf("heartbeat disconnect room=%s user=%s elapsed=%s score=%d", room.room.RoomId, username, elapsed, player.Score)
			if err := s.finishPlayerLocked(room, username, pb.PlayerFinishReason_PLAYER_FINISH_REASON_DISCONNECTED); err != nil {
				log.Printf("finish disconnected player failed: %v", err)
			}
		}
	}
}

func (s *Server) snapshotConnectionsLocked(room *roomState) []*clientConn {
	conns := make([]*clientConn, 0, len(room.conns))
	for _, conn := range room.conns {
		conns = append(conns, conn)
	}
	return conns
}

func (s *Server) broadcastToConnections(conns []*clientConn, message proto.Message) {
	// 服务端广播统一包成 WsMessage，确保前后端只处理一种 WS 二进制帧格式。
	frame, err := wrapServerMessage(message)
	if err != nil {
		log.Printf("wrap broadcast failed: %v", err)
		return
	}
	payload, err := proto.Marshal(frame)
	if err != nil {
		log.Printf("marshal broadcast failed: %v", err)
		return
	}
	for _, conn := range conns {
		conn.mu.Lock()
		_ = conn.conn.WriteMessage(websocket.BinaryMessage, payload)
		conn.mu.Unlock()
	}
}

func wrapServerMessage(message proto.Message) (*pb.WsMessage, error) {
	switch msg := message.(type) {
	case *pb.ScoreBroadcast:
		return &pb.WsMessage{Payload: &pb.WsMessage_ScoreBroadcast{ScoreBroadcast: msg}}, nil
	case *pb.RoomStateBroadcast:
		return &pb.WsMessage{Payload: &pb.WsMessage_RoomStateBroadcast{RoomStateBroadcast: msg}}, nil
	case *pb.GameFinishedBroadcast:
		return &pb.WsMessage{Payload: &pb.WsMessage_GameFinishedBroadcast{GameFinishedBroadcast: msg}}, nil
	default:
		return nil, fmt.Errorf("unsupported ws server message: %T", message)
	}
}

func (s *Server) decodeRequest(r *http.Request, msg proto.Message) error {
	if r.Method != http.MethodPost {
		return fmt.Errorf("method not allowed")
	}
	defer r.Body.Close()
	payload, err := io.ReadAll(r.Body)
	if err != nil {
		return err
	}
	if err := proto.Unmarshal(payload, msg); err != nil {
		return err
	}
	return nil
}

func (s *Server) writeProto(w http.ResponseWriter, status int, msg proto.Message) {
	payload, err := proto.Marshal(msg)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/x-protobuf")
	w.WriteHeader(status)
	_, _ = w.Write(payload)
}

func (s *Server) lookupRoomPlayer(roomID, username string) (*roomState, *playerState, error) {
	room, ok := s.rooms[roomID]
	if !ok {
		return nil, nil, errRoomNotFound
	}
	player, ok := room.players[username]
	if !ok {
		return nil, nil, errPlayerForbidden
	}
	return room, player, nil
}

var (
	errRoomNotFound   = errors.New("room not found")
	errPlayerForbidden = errors.New("player not in room")
)

func (s *Server) writeLookupError(w http.ResponseWriter, err error) {
	switch err {
	case errRoomNotFound:
		http.Error(w, err.Error(), http.StatusNotFound)
	case errPlayerForbidden:
		http.Error(w, err.Error(), http.StatusForbidden)
	default:
		http.Error(w, err.Error(), http.StatusInternalServerError)
	}
}

func roomScores(room *roomState) []*pb.RoomPlayerScore {
	scores := make([]*pb.RoomPlayerScore, 0, len(room.playerOrder))
	for _, username := range room.playerOrder {
		ps := room.players[username]
		scores = append(scores, &pb.RoomPlayerScore{
			Username:     username,
			Score:        ps.Score,
			Finished:     ps.Player.Status == pb.PlayerStatus_PLAYER_STATUS_FINISHED,
			Status:       ps.Player.Status,
			FinishReason: ps.FinishReason,
		})
	}
	return scores
}

func (s *Server) buildRoomStateBroadcastLocked(room *roomState, username string) *pb.RoomStateBroadcast {
	broadcast := &pb.RoomStateBroadcast{
		Room:         cloneRoom(room.room),
		Scores:       roomScores(room),
		RoomFinished: room.room.Status == pb.RoomStatus_ROOM_STATUS_FINISHED,
		UpdatedAt:    s.cfg.Now().UnixMilli(),
	}
	if room.result != nil {
		broadcast.Result = service.BuildRoomResult(room.result, username)
	}
	return broadcast
}

func (s *Server) buildRoomStateBroadcastsForConnectionsLocked(room *roomState) map[*clientConn]*pb.RoomStateBroadcast {
	broadcasts := make(map[*clientConn]*pb.RoomStateBroadcast, len(room.conns))
	for username, conn := range room.conns {
		if conn == nil {
			continue
		}
		broadcasts[conn] = s.buildRoomStateBroadcastLocked(room, username)
	}
	return broadcasts
}

func (s *Server) broadcastRoomStateBroadcasts(connections []*clientConn, broadcasts map[*clientConn]*pb.RoomStateBroadcast) {
	for _, conn := range connections {
		broadcast, ok := broadcasts[conn]
		if !ok || broadcast == nil {
			continue
		}
		s.broadcastToConnections([]*clientConn{conn}, broadcast)
	}
}

func cloneRoom(room *pb.Room) *pb.Room {
	return proto.Clone(room).(*pb.Room)
}

func updateRoomPlayer(room *pb.Room, player *pb.Player) {
	for i, current := range room.Players {
		if current.Username == player.Username {
			room.Players[i] = proto.Clone(player).(*pb.Player)
			return
		}
	}
}

func allPlayers(room *roomState, fn func(*playerState) bool) bool {
	if len(room.playerOrder) != 2 {
		return false
	}
	for _, username := range room.playerOrder {
		if !fn(room.players[username]) {
			return false
		}
	}
	return true
}

func newRoomID(now time.Time) string {
	_ = now
	return fmt.Sprintf("%06d", rand.Intn(1000000))
}

func (s *Server) newUniqueRoomIDLocked(now time.Time) string {
	for {
		s.randMu.Lock()
		candidate := newRoomID(now)
		s.randMu.Unlock()
		if _, exists := s.rooms[candidate]; !exists {
			return candidate
		}
	}
}
