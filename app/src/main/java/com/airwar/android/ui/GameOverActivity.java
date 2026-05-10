package com.airwar.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airwar.android.R;
import com.airwar.android.net.MultiplayerSession;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hitsz.aircraftwar.backend.AircraftWar;

public class GameOverActivity extends AppCompatActivity implements MultiplayerSession.Listener {
    public static final String EXTRA_SCORE = "extra_score";
    public static final String EXTRA_DURATION_SEC = "extra_duration_sec";
    public static final String EXTRA_DIFFICULTY = MenuActivity.EXTRA_DIFFICULTY;
    public static final String EXTRA_IS_MULTIPLAYER = GameActivity.EXTRA_IS_MULTIPLAYER;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final com.airwar.android.net.MultiplayerApi multiplayerApi = new com.airwar.android.net.MultiplayerApi();
    private TextView scoreText;
    private TextView statusText;
    private TextView opponentStatusText;
    private int score;
    private boolean multiplayerMode;
    private String difficulty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_over);

        score = getIntent().getIntExtra(EXTRA_SCORE, 0);
        int durationSec = getIntent().getIntExtra(EXTRA_DURATION_SEC, 0);
        multiplayerMode = getIntent().getBooleanExtra(EXTRA_IS_MULTIPLAYER, false);
        difficulty = getIntent().getStringExtra(EXTRA_DIFFICULTY);

        TextView headerTitle = findViewById(R.id.game_over_header);
        scoreText = findViewById(R.id.game_over_score);
        TextView durationText = findViewById(R.id.game_over_duration);
        statusText = findViewById(R.id.game_over_status);
        opponentStatusText = findViewById(R.id.game_over_opponent_status);
        TextView submitButton = findViewById(R.id.game_over_submit_button);

        headerTitle.setText(getString(R.string.game_over_title));
        bindBottomNav();

        scoreText.setText(getString(R.string.game_over_score_format, score));
        durationText.setText(getString(R.string.game_over_duration_format, durationSec));
        statusText.setText(getString(R.string.game_over_status_normal));
        opponentStatusText.setText(getString(R.string.game_over_opponent_playing));

        if (multiplayerMode) {
            MultiplayerSession.getInstance().addListener(this);
            syncStateOnce();
        } else {
            opponentStatusText.setText(getString(R.string.game_over_single_player_ready));
        }

        submitButton.setOnClickListener(v -> {
            if (multiplayerMode) {
                // 结束页提交按钮现在只承担“查看全服榜”的作用，最终成绩已经由服务端负责入榜。
                MultiplayerSession.getInstance().disconnect();
                Intent leaderboardIntent = new Intent(this, LeaderboardActivity.class);
                startActivity(leaderboardIntent);
            } else {
                Intent replayIntent = new Intent(this, GameActivity.class);
                replayIntent.putExtra(GameActivity.EXTRA_DIFFICULTY, difficulty == null ? MenuActivity.DIFFICULTY_NORMAL : difficulty);
                replayIntent.putExtra(GameActivity.EXTRA_IS_MULTIPLAYER, false);
                startActivity(replayIntent);
            }
            finish();
        });
        submitButton.setText(multiplayerMode ? getString(R.string.game_over_submit) : getString(R.string.game_over_replay));
    }

    @Override
    protected void onDestroy() {
        if (multiplayerMode) {
            MultiplayerSession.getInstance().removeListener(this);
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void bindBottomNav() {
        TextView homeLabel = findViewById(R.id.nav_home_label);
        TextView rankingLabel = findViewById(R.id.nav_ranking_label);

        homeLabel.setTextColor(ContextCompat.getColor(this, R.color.ui2_body));
        rankingLabel.setTextColor(ContextCompat.getColor(this, R.color.ui2_accent));

        findViewById(R.id.nav_home).setOnClickListener(v -> {
            MultiplayerSession.getInstance().clearRoomContext();
            LocalMultiplayerPrefs.clearRoomId(this);
            Intent intent = new Intent(this, MenuActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });

        findViewById(R.id.nav_ranking).setOnClickListener(v -> {
        });
    }

    // 结束页仍然监听联机会话，是为了支持“自己已结束、对手仍在游戏中”时继续看到对手状态变化。
    @Override
    public void onSessionUpdated(MultiplayerSession.Snapshot snapshot) {
        if (!multiplayerMode) {
            return;
        }
        AircraftWar.RoomPlayerScore selfScore = findSelfScore(snapshot.scores(), snapshot.username());
        AircraftWar.RoomPlayerScore opponentScore = findOpponentScore(snapshot.scores(), snapshot.username());

        if (selfScore != null) {
            score = selfScore.getScore();
            scoreText.setText(getString(R.string.game_over_score_format, score));
            if (selfScore.getFinishReason() == AircraftWar.PlayerFinishReason.PLAYER_FINISH_REASON_DISCONNECTED) {
                statusText.setText(getString(R.string.game_over_status_disconnected));
            } else if (!selfScore.getFinished()) {
                statusText.setText(getString(R.string.game_over_status_observing));
            } else {
                statusText.setText(getString(R.string.game_over_status_normal));
            }
        }

        if (snapshot.roomFinished() && snapshot.result() != null) {
            opponentStatusText.setText(getString(
                    R.string.game_over_result_summary,
                    readableGameResult(snapshot.result().getSelfResult()),
                    snapshot.result().getWinnerUsername().isEmpty() ? getString(R.string.game_over_result_draw) : snapshot.result().getWinnerUsername()
            ));
        } else if (opponentScore != null && opponentScore.getFinished()) {
            opponentStatusText.setText(getString(R.string.game_over_opponent_finished));
        } else {
            opponentStatusText.setText(getString(R.string.game_over_opponent_playing));
        }
    }

    private void syncStateOnce() {
        MultiplayerSession.Snapshot snapshot = MultiplayerSession.getInstance().snapshot();
        if (snapshot.roomId().isEmpty() || snapshot.username().isEmpty()) {
            return;
        }
        // 结束页初始化时主动拉一次状态，避免仅靠 WS 推送时页面初次进入没有完整房间快照。
        ioExecutor.execute(() -> {
            try {
                AircraftWar.GetRoomStateResponse response = multiplayerApi.getRoomState(
                        snapshot.baseUrl(),
                        snapshot.roomId(),
                        snapshot.username()
                );
                MultiplayerSession.getInstance().applyRoomState(
                        response.getRoom(),
                        response.getScoresList(),
                        response.getRoomFinished(),
                        response.hasResult() ? response.getResult() : null
                );
                if (response.getRoomFinished()) {
                    AircraftWar.GetRoomResultResponse resultResponse = multiplayerApi.getRoomResult(
                            snapshot.baseUrl(),
                            snapshot.roomId(),
                            snapshot.username()
                    );
                    MultiplayerSession.getInstance().applyRoomState(
                            response.getRoom(),
                            response.getScoresList(),
                            true,
                            resultResponse.getResult()
                    );
                }
            } catch (IOException ignored) {
            }
        });
    }

    private AircraftWar.RoomPlayerScore findSelfScore(List<AircraftWar.RoomPlayerScore> scores, String username) {
        for (AircraftWar.RoomPlayerScore score : scores) {
            if (score.getUsername().equals(username)) {
                return score;
            }
        }
        return null;
    }

    private AircraftWar.RoomPlayerScore findOpponentScore(List<AircraftWar.RoomPlayerScore> scores, String username) {
        for (AircraftWar.RoomPlayerScore score : scores) {
            if (!score.getUsername().equals(username)) {
                return score;
            }
        }
        return null;
    }

    private String readableGameResult(AircraftWar.GameResultType resultType) {
        return switch (resultType) {
            case GAME_RESULT_WIN -> getString(R.string.game_result_win);
            case GAME_RESULT_LOSE -> getString(R.string.game_result_lose);
            case GAME_RESULT_DRAW -> getString(R.string.game_result_draw);
            default -> getString(R.string.game_result_pending);
        };
    }
}
