package entity

import pb "github.com/acc1111/aircraft-war-hitsz/backend/proto"

type PersistedResult struct {
	RoomID              string
	PlayerAUsername     string
	PlayerBUsername     string
	PlayerAScore        int32
	PlayerBScore        int32
	WinnerUsername      string
	FinishedAt          int64
	PlayerAFinishReason pb.PlayerFinishReason
	PlayerBFinishReason pb.PlayerFinishReason
}
