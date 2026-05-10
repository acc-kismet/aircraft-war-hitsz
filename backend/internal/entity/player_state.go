package entity

import (
	"time"

	pb "github.com/acc1111/aircraft-war-hitsz/backend/proto"
)

type PlayerState struct {
	Player         *pb.Player
	Score          int32
	FinishReason   pb.PlayerFinishReason
	LastHeartbeat  time.Time
	DefeatEventIDs map[string]struct{}
}
