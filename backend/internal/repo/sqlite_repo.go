package repo

import (
	"database/sql"
	"strings"

	"github.com/acc1111/aircraft-war-hitsz/backend/internal/entity"
	pb "github.com/acc1111/aircraft-war-hitsz/backend/proto"
)

type SQLiteRepo struct {
	db *sql.DB
}

func NewSQLiteRepo(db *sql.DB) *SQLiteRepo {
	return &SQLiteRepo{db: db}
}

func (r *SQLiteRepo) Init() error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS room_results (
			room_id TEXT PRIMARY KEY,
			player_a_username TEXT NOT NULL,
			player_b_username TEXT NOT NULL,
			player_a_score INTEGER NOT NULL,
			player_b_score INTEGER NOT NULL,
			winner_username TEXT NOT NULL,
			player_a_finish_reason INTEGER NOT NULL,
			player_b_finish_reason INTEGER NOT NULL,
			finished_at INTEGER NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS leaderboard (
			username TEXT PRIMARY KEY,
			best_score INTEGER NOT NULL,
			win_count INTEGER NOT NULL,
			game_count INTEGER NOT NULL,
			avatar_id TEXT NOT NULL DEFAULT '',
			updated_at INTEGER NOT NULL
		)`,
		`ALTER TABLE leaderboard ADD COLUMN avatar_id TEXT NOT NULL DEFAULT ''`,
	}
	for _, stmt := range stmts {
		if _, err := r.db.Exec(stmt); err != nil {
			if strings.Contains(stmt, "ALTER TABLE leaderboard ADD COLUMN avatar_id") && strings.Contains(err.Error(), "duplicate column name") {
				continue
			}
			return err
		}
	}
	return nil
}

func (r *SQLiteRepo) FindRoomResult(roomID string) (*entity.PersistedResult, error) {
	row := &entity.PersistedResult{}
	err := r.db.QueryRow(`SELECT room_id, player_a_username, player_b_username, player_a_score, player_b_score, winner_username, finished_at, player_a_finish_reason, player_b_finish_reason FROM room_results WHERE room_id = ?`, roomID).
		Scan(&row.RoomID, &row.PlayerAUsername, &row.PlayerBUsername, &row.PlayerAScore, &row.PlayerBScore, &row.WinnerUsername, &row.FinishedAt, &row.PlayerAFinishReason, &row.PlayerBFinishReason)
	if err != nil {
		return nil, err
	}
	return row, nil
}

func (r *SQLiteRepo) ListLeaderboard(limit, offset int32) ([]*pb.LeaderboardEntry, error) {
	rows, err := r.db.Query(`SELECT username, best_score, win_count, game_count, updated_at, avatar_id FROM leaderboard ORDER BY best_score DESC, updated_at ASC, username ASC LIMIT ? OFFSET ?`, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	entries := make([]*pb.LeaderboardEntry, 0)
	for rows.Next() {
		entry := &pb.LeaderboardEntry{}
		if err := rows.Scan(&entry.Username, &entry.BestScore, &entry.WinCount, &entry.GameCount, &entry.UpdatedAt, &entry.AvatarId); err != nil {
			return nil, err
		}
		entries = append(entries, entry)
	}
	return entries, nil
}

func (r *SQLiteRepo) SaveRoomResult(result *entity.PersistedResult) error {
	_, err := r.db.Exec(`INSERT INTO room_results (room_id, player_a_username, player_b_username, player_a_score, player_b_score, winner_username, player_a_finish_reason, player_b_finish_reason, finished_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(room_id) DO UPDATE SET player_a_score = excluded.player_a_score, player_b_score = excluded.player_b_score, winner_username = excluded.winner_username,
		player_a_finish_reason = excluded.player_a_finish_reason, player_b_finish_reason = excluded.player_b_finish_reason, finished_at = excluded.finished_at`,
		result.RoomID, result.PlayerAUsername, result.PlayerBUsername, result.PlayerAScore, result.PlayerBScore, result.WinnerUsername, result.PlayerAFinishReason, result.PlayerBFinishReason, result.FinishedAt)
	return err
}

func (r *SQLiteRepo) UpsertLeaderboardProgress(player *pb.Player, score int32, now int64) error {
	_, err := r.db.Exec(`INSERT INTO leaderboard (username, best_score, win_count, game_count, avatar_id, updated_at)
		VALUES (?, ?, 0, 1, ?, ?)
		ON CONFLICT(username) DO UPDATE SET
		best_score = CASE WHEN excluded.best_score > leaderboard.best_score THEN excluded.best_score ELSE leaderboard.best_score END,
		game_count = leaderboard.game_count + 1,
		avatar_id = excluded.avatar_id,
		updated_at = CASE WHEN excluded.best_score > leaderboard.best_score THEN excluded.updated_at ELSE leaderboard.updated_at END`, player.Username, score, player.AvatarId, now)
	return err
}

func (r *SQLiteRepo) IncrementWinnerWinCount(username string) error {
	_, err := r.db.Exec(`UPDATE leaderboard SET win_count = win_count + 1 WHERE username = ?`, username)
	return err
}
