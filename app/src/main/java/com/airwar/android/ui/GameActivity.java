package com.airwar.android.ui;

import android.content.pm.ActivityInfo;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;

import com.airwar.android.R;
import com.airwar.android.audio.AndroidAudioManager;
import com.airwar.android.net.MultiplayerApi;
import com.airwar.android.net.MultiplayerSession;
import com.airwar.android.view.GameSurfaceView;
import com.airwar.core.engine.GameStateSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hitsz.aircraftwar.backend.AircraftWar;

public class GameActivity extends AppCompatActivity implements MultiplayerSession.Listener {
    public static final String EXTRA_DIFFICULTY = MenuActivity.EXTRA_DIFFICULTY;
    public static final String EXTRA_IS_MULTIPLAYER = "com.airwar.android.extra.IS_MULTIPLAYER";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final MultiplayerApi multiplayerApi = new MultiplayerApi();
    private AndroidAudioManager audioManager;
    private String difficulty;
    private boolean multiplayerMode;
    private TextView hudScore;
    private TextView hudHp;
    private TextView hudRoomState;
    private TextView hudMultiplayerSelfScore;
    private TextView hudMultiplayerOpponentScore;
    private GameSurfaceView gameSurfaceView;
    private long gameStartMs;
    private int lastReportedScore;
    private int lastGameOverEvents;
    private boolean gameOverNavigated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_game);

        multiplayerMode = getIntent().getBooleanExtra(EXTRA_IS_MULTIPLAYER, false);
        MultiplayerSession.Snapshot sessionSnapshot = MultiplayerSession.getInstance().snapshot();
        if (multiplayerMode && (sessionSnapshot.roomId().isEmpty() || sessionSnapshot.username().isEmpty())) {
            finishToMenu();
            return;
        }

        difficulty = getIntent().getStringExtra(EXTRA_DIFFICULTY);
        audioManager = new AndroidAudioManager(this);
        audioManager.setEnabled(LocalMultiplayerPrefs.isSoundEnabled(this));
        gameStartMs = System.currentTimeMillis();

        hudScore = findViewById(R.id.hud_score);
        hudHp = findViewById(R.id.hud_hp);
        hudRoomState = findViewById(R.id.hud_room_state);
        hudMultiplayerSelfScore = findViewById(R.id.hud_multiplayer_self_score);
        hudMultiplayerOpponentScore = findViewById(R.id.hud_multiplayer_opponent_score);
        ToggleButton soundToggle = findViewById(R.id.hud_sound_toggle);
        soundToggle.setChecked(audioManager.isEnabled());
        soundToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            audioManager.setEnabled(isChecked);
            LocalMultiplayerPrefs.saveSoundEnabled(this, isChecked);
            if (isChecked) {
                gameSurfaceView.post(() -> gameSurfaceView.refreshAudioTrack());
            }
        });

        gameSurfaceView = findViewById(R.id.game_surface);
        gameSurfaceView.setDifficulty(difficulty);
        gameSurfaceView.setAudioManager(audioManager);
        gameSurfaceView.setSnapshotListener(this::updateHud);

        if (multiplayerMode) {
            // 进入对战后立即接入联机会话，后续比分广播和掉线恢复都依赖这个共享状态。
            MultiplayerSession.getInstance().addListener(this);
            MultiplayerSession.getInstance().connectIfNeeded();
            MultiplayerSession.Snapshot liveSnapshot = MultiplayerSession.getInstance().snapshot();
            if (difficulty == null || difficulty.isEmpty()) {
                difficulty = MultiplayerApi.fromRoomDifficulty(liveSnapshot.room().getDifficulty());
                gameSurfaceView.setDifficulty(difficulty);
            }
            updateConnectionHud(liveSnapshot);
            syncRoomStateOnce();
        } else {
            hudRoomState.setText(getString(R.string.hud_room_state_single_player));
            hudMultiplayerSelfScore.setVisibility(android.view.View.GONE);
            hudMultiplayerOpponentScore.setVisibility(android.view.View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (audioManager != null) {
            audioManager.playBgmGame();
        }
    }

    @Override
    protected void onPause() {
        if (audioManager != null) {
            audioManager.stopAllBgm();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (multiplayerMode) {
            MultiplayerSession.getInstance().removeListener(this);
        }
        ioExecutor.shutdownNow();
        if (audioManager != null) {
            audioManager.release();
        }
        super.onDestroy();
    }

    private void updateHud(GameStateSnapshot snapshot) {
        if (hudScore != null) {
            hudScore.setText(getString(R.string.hud_score_value, snapshot.score()));
        }
        if (hudHp != null) {
            hudHp.setText(getString(R.string.hud_hp_value, snapshot.heroHp()));
        }

        reportScoreGain(snapshot.score());

        if (!gameOverNavigated && snapshot.gameOverEvents() > lastGameOverEvents) {
            if (multiplayerMode) {
                // 本地游戏结束时先发主动结束事件，再跳到结束页，保证服务端能尽快感知玩家结束。
                MultiplayerSession.getInstance().sendGameOverEvent(snapshot.score(), "local_game_over");
            }
            gameOverNavigated = true;
            navigateToGameOver(snapshot.score());
        }
        lastGameOverEvents = snapshot.gameOverEvents();
    }

    @Override
    public void onSessionUpdated(MultiplayerSession.Snapshot snapshot) {
        if (!multiplayerMode) {
            return;
        }
        updateConnectionHud(snapshot);
        updateMultiplayerScoreHud(snapshot);

        // 如果服务端已经把当前玩家判定为结束，则不允许继续停留在战斗中，直接进入结束页。
        AircraftWar.RoomPlayerScore selfScore = findSelfScore(snapshot.scores(), snapshot.username());
        if (!gameOverNavigated && selfScore != null && selfScore.getFinished()) {
            gameOverNavigated = true;
            navigateToGameOver(selfScore.getScore());
        }

        if (selfScore != null && hudScore != null) {
            // HUD 尽量展示服务端权威分数，避免本地和服务端出现短暂漂移时用户看到两套分值。
            hudScore.setText(getString(R.string.hud_score_value, selfScore.getScore()));
        }
    }

    private void updateConnectionHud(MultiplayerSession.Snapshot snapshot) {
        if (hudRoomState == null) {
            return;
        }
        String connection = switch (snapshot.connectionState()) {
            case CONNECTED -> getString(R.string.multiplayer_connection_connected);
            case CONNECTING -> getString(R.string.multiplayer_connection_connecting);
            case ERROR -> getString(R.string.multiplayer_connection_error);
            default -> getString(R.string.multiplayer_connection_disconnected);
        };
        hudRoomState.setText(getString(
                R.string.hud_room_state_value,
                snapshot.roomId().isEmpty() ? "-" : snapshot.roomId(),
                connection
        ));
    }

    private void updateMultiplayerScoreHud(MultiplayerSession.Snapshot snapshot) {
        if (hudMultiplayerSelfScore == null || hudMultiplayerOpponentScore == null) {
            return;
        }
        AircraftWar.RoomPlayerScore selfScore = findSelfScore(snapshot.scores(), snapshot.username());
        AircraftWar.Player opponent = findOpponent(snapshot.room(), snapshot.username());
        AircraftWar.RoomPlayerScore opponentScore = opponent == null ? null : findSelfScore(snapshot.scores(), opponent.getUsername());
        String selfName = snapshot.username().isEmpty() ? getString(R.string.multiplayer_unknown_opponent) : snapshot.username();
        String opponentName = opponent == null ? getString(R.string.multiplayer_unknown_opponent) : opponent.getUsername();
        hudMultiplayerSelfScore.setText(getString(R.string.hud_multiplayer_score_line, selfName, selfScore == null ? 0 : selfScore.getScore()));
        hudMultiplayerOpponentScore.setText(getString(R.string.hud_multiplayer_score_line, opponentName, opponentScore == null ? 0 : opponentScore.getScore()));
    }

    private AircraftWar.Player findOpponent(AircraftWar.Room room, String username) {
        if (room == null) {
            return null;
        }
        for (AircraftWar.Player player : room.getPlayersList()) {
            if (!player.getUsername().equals(username)) {
                return player;
            }
        }
        return null;
    }

    private AircraftWar.RoomPlayerScore findSelfScore(List<AircraftWar.RoomPlayerScore> scores, String username) {
        for (AircraftWar.RoomPlayerScore score : scores) {
            if (score.getUsername().equals(username)) {
                return score;
            }
        }
        return null;
    }

    // 这里用分数增量近似推断“击败了一架什么敌机”，目的是在不改现有单机核心逻辑的前提下把击败事件接到真实后端。
    private void reportScoreGain(int currentScore) {
        if (!multiplayerMode) {
            return;
        }
        if (currentScore <= lastReportedScore) {
            return;
        }

        int delta = currentScore - lastReportedScore;
        lastReportedScore = currentScore;
        while (delta >= 50) {
            MultiplayerSession.getInstance().sendDefeatEvent(AircraftWar.EnemyType.ENEMY_TYPE_BOSS, 50);
            delta -= 50;
        }
        while (delta >= 20) {
            MultiplayerSession.getInstance().sendDefeatEvent(AircraftWar.EnemyType.ENEMY_TYPE_ELITE, 20);
            delta -= 20;
        }
        while (delta >= 10) {
            MultiplayerSession.getInstance().sendDefeatEvent(AircraftWar.EnemyType.ENEMY_TYPE_MOB, 10);
            delta -= 10;
        }
    }

    private void syncRoomStateOnce() {
        if (!multiplayerMode) {
            return;
        }
        MultiplayerSession.Snapshot snapshot = MultiplayerSession.getInstance().snapshot();
        if (snapshot.roomId().isEmpty() || snapshot.username().isEmpty()) {
            return;
        }

        // 进入游戏后先拉一次房间状态，保证掉线恢复、对局继续和最终结算都能从同一个服务端状态起步。
        ioExecutor.execute(() -> {
            try {
                AircraftWar.GetRoomStateResponse response = multiplayerApi.getRoomState(snapshot.baseUrl(), snapshot.roomId(), snapshot.username());
                MultiplayerSession.getInstance().applyRoomState(
                        response.getRoom(),
                        response.getScoresList(),
                        response.getRoomFinished(),
                        response.hasResult() ? response.getResult() : null
                );
            } catch (IOException ignored) {
            }
        });
    }

    private void navigateToGameOver(int finalScore) {
        int durationSec = (int) Math.max(1L, (System.currentTimeMillis() - gameStartMs) / 1000L);
        Intent intent = new Intent(this, GameOverActivity.class);
        intent.putExtra(GameOverActivity.EXTRA_SCORE, finalScore);
        intent.putExtra(GameOverActivity.EXTRA_DURATION_SEC, durationSec);
        intent.putExtra(GameOverActivity.EXTRA_DIFFICULTY, difficulty == null ? MenuActivity.DIFFICULTY_NORMAL : difficulty);
        intent.putExtra(GameOverActivity.EXTRA_IS_MULTIPLAYER, multiplayerMode);
        startActivity(intent);
        finish();
    }

    private void finishToMenu() {
        Intent intent = new Intent(this, MenuActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
