package entity

import pb "github.com/acc1111/aircraft-war-hitsz/backend/proto"

type RoomState struct {
	Room            *pb.Room
	PlayerOrder     []string
	Players         map[string]*PlayerState
	Result          *PersistedResult
	WinCountApplied bool
}
