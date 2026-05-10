package service

import (
	"github.com/acc1111/aircraft-war-hitsz/backend/internal/entity"
	pb "github.com/acc1111/aircraft-war-hitsz/backend/proto"
)

func EnemyScore(enemyType pb.EnemyType) int32 {
	switch enemyType {
	case pb.EnemyType_ENEMY_TYPE_ELITE:
		return 20
	case pb.EnemyType_ENEMY_TYPE_BOSS:
		return 50
	default:
		return 10
	}
}

func NormalizeRoomDifficulty(difficulty pb.RoomDifficulty) pb.RoomDifficulty {
	switch difficulty {
	case pb.RoomDifficulty_ROOM_DIFFICULTY_EASY,
		pb.RoomDifficulty_ROOM_DIFFICULTY_HARD:
		return difficulty
	default:
		return pb.RoomDifficulty_ROOM_DIFFICULTY_NORMAL
	}
}

func FinalizeResult(roomID string, playerOrder []string, players map[string]*entity.PlayerState, finishedAt int64) *entity.PersistedResult {
	aName := playerOrder[0]
	bName := playerOrder[1]
	a := players[aName]
	b := players[bName]
	result := &entity.PersistedResult{
		RoomID:              roomID,
		PlayerAUsername:     aName,
		PlayerBUsername:     bName,
		PlayerAScore:        a.Score,
		PlayerBScore:        b.Score,
		FinishedAt:          finishedAt,
		PlayerAFinishReason: a.FinishReason,
		PlayerBFinishReason: b.FinishReason,
	}
	switch {
	case a.Score > b.Score:
		result.WinnerUsername = aName
	case b.Score > a.Score:
		result.WinnerUsername = bName
	default:
		result.WinnerUsername = ""
	}
	return result
}

func BuildRoomResult(result *entity.PersistedResult, username string) *pb.RoomResult {
	return &pb.RoomResult{
		RoomId:              result.RoomID,
		PlayerAUsername:     result.PlayerAUsername,
		PlayerBUsername:     result.PlayerBUsername,
		PlayerAScore:        result.PlayerAScore,
		PlayerBScore:        result.PlayerBScore,
		WinnerUsername:      result.WinnerUsername,
		SelfResult:          SelfResult(result, username),
		FinishedAt:          result.FinishedAt,
		PlayerAFinishReason: result.PlayerAFinishReason,
		PlayerBFinishReason: result.PlayerBFinishReason,
	}
}

func SelfResult(result *entity.PersistedResult, username string) pb.GameResultType {
	if username == "" {
		return pb.GameResultType_GAME_RESULT_UNSPECIFIED
	}
	if result.WinnerUsername == "" {
		return pb.GameResultType_GAME_RESULT_DRAW
	}
	if result.WinnerUsername == username {
		return pb.GameResultType_GAME_RESULT_WIN
	}
	return pb.GameResultType_GAME_RESULT_LOSE
}
