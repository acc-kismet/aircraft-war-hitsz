package com.airwar.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airwar.android.R;
import com.airwar.android.net.MultiplayerApi;
import com.airwar.android.net.MultiplayerSession;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hitsz.aircraftwar.backend.AircraftWar;

public class RoomActivity extends AppCompatActivity implements MultiplayerSession.Listener {
    public static final String EXTRA_DIFFICULTY = MenuActivity.EXTRA_DIFFICULTY;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final MultiplayerApi multiplayerApi = new MultiplayerApi();

    private TextView roomCodeView;
    private TextView roomOwnerView;
    private TextView roomStatusView;
    private TextView roomStatusDetailView;
    private TextView roomSelfStateView;
    private TextView roomOpponentStateView;
    private TextView roomConnectionView;
    private TextView roomDifficultyValue;
    private Button readyButton;
    private Button syncButton;
    private Button startButton;
    private ToggleButton roomDifficultyEasy;
    private ToggleButton roomDifficultyNormal;
    private ToggleButton roomDifficultyHard;
    private String selectedDifficulty;
    private boolean gameNavigated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);

        selectedDifficulty = getIntent().getStringExtra(EXTRA_DIFFICULTY);
        if (selectedDifficulty == null || selectedDifficulty.isEmpty()) {
            selectedDifficulty = MenuActivity.DIFFICULTY_NORMAL;
        }

        MultiplayerSession.Snapshot snapshot = MultiplayerSession.getInstance().snapshot();
        if (snapshot.roomId().isEmpty() || snapshot.username().isEmpty()) {
            String savedBaseUrl = LocalMultiplayerPrefs.getBaseUrl(this);
            String savedUsername = LocalMultiplayerPrefs.getUsername(this);
            String savedRoomId = LocalMultiplayerPrefs.getRoomId(this);
            if (savedRoomId.isEmpty() || savedUsername.isEmpty()) {
                finishToMenu();
                return;
            }
            MultiplayerSession.getInstance().configure(savedBaseUrl, savedRoomId, savedUsername, LocalMultiplayerPrefs.getAvatarId(this));
        }

        TextView headerTitle = findViewById(R.id.room_header);
        roomCodeView = findViewById(R.id.room_code_value);
        roomOwnerView = findViewById(R.id.room_owner_value);
        roomStatusView = findViewById(R.id.room_status_value);
        roomStatusDetailView = findViewById(R.id.room_status_detail_value);
        roomSelfStateView = findViewById(R.id.room_self_state_value);
        roomOpponentStateView = findViewById(R.id.room_opponent_state_value);
        roomConnectionView = findViewById(R.id.room_connection_value);
        roomDifficultyValue = findViewById(R.id.room_difficulty_value);
        readyButton = findViewById(R.id.button_room_ready);
        syncButton = findViewById(R.id.button_room_sync);
        startButton = findViewById(R.id.button_room_start);
        roomDifficultyEasy = findViewById(R.id.button_room_difficulty_easy);
        roomDifficultyNormal = findViewById(R.id.button_room_difficulty_normal);
        roomDifficultyHard = findViewById(R.id.button_room_difficulty_hard);

        headerTitle.setText(getString(R.string.multiplayer_room_title));
        bindBottomNav();

        readyButton.setOnClickListener(v -> readyRoom());
        syncButton.setOnClickListener(v -> syncRoomState());
        startButton.setOnClickListener(v -> startGame());
        roomDifficultyEasy.setOnClickListener(v -> updateRoomDifficulty(MenuActivity.DIFFICULTY_EASY));
        roomDifficultyNormal.setOnClickListener(v -> updateRoomDifficulty(MenuActivity.DIFFICULTY_NORMAL));
        roomDifficultyHard.setOnClickListener(v -> updateRoomDifficulty(MenuActivity.DIFFICULTY_HARD));

        MultiplayerSession.getInstance().connectIfNeeded();
        MultiplayerSession.getInstance().addListener(this);
        renderSnapshot(MultiplayerSession.getInstance().snapshot());
        syncRoomState();
    }

    @Override
    protected void onDestroy() {
        MultiplayerSession.getInstance().removeListener(this);
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onSessionUpdated(MultiplayerSession.Snapshot snapshot) {
        renderSnapshot(snapshot);
        if (!gameNavigated && shouldEnterGame(snapshot)) {
            gameNavigated = true;
            Intent gameIntent = new Intent(this, GameActivity.class);
            gameIntent.putExtra(EXTRA_DIFFICULTY, selectedDifficulty);
            gameIntent.putExtra(GameActivity.EXTRA_IS_MULTIPLAYER, true);
            startActivity(gameIntent);
            finish();
            return;
        }
    }

    private boolean shouldEnterGame(MultiplayerSession.Snapshot snapshot) {
        if (snapshot.room() != null && snapshot.room().getStatus() == AircraftWar.RoomStatus.ROOM_STATUS_PLAYING) {
            return true;
        }
        for (AircraftWar.RoomPlayerScore score : snapshot.scores()) {
            if (score.getUsername().equals(snapshot.username())
                    && score.getStatus() == AircraftWar.PlayerStatus.PLAYER_STATUS_PLAYING) {
                return true;
            }
        }
        return false;
    }

    private void renderSnapshot(MultiplayerSession.Snapshot snapshot) {
        AircraftWar.Room room = snapshot.room();
        AircraftWar.Player selfPlayer = findPlayer(room, snapshot.username());
        AircraftWar.Player opponentPlayer = findOpponent(room, snapshot.username());
        AircraftWar.Player hostPlayer = findHost(room);
        roomCodeView.setText(snapshot.roomId().isEmpty() ? "-" : snapshot.roomId());
        roomOwnerView.setText(hostPlayer == null ? "-" : hostPlayer.getUsername());
        roomStatusView.setText(readableStageTitle(snapshot, selfPlayer, opponentPlayer));
        roomStatusDetailView.setText(readableStageDetail(snapshot, selfPlayer, opponentPlayer));
        roomSelfStateView.setText(getString(
                R.string.multiplayer_player_line,
                snapshot.username().isEmpty() ? "-" : snapshot.username(),
                readablePlayerState(selfPlayer == null ? null : selfPlayer.getStatus())
        ));
        roomOpponentStateView.setText(getString(
                R.string.multiplayer_player_line,
                opponentPlayer == null ? getString(R.string.multiplayer_unknown_opponent) : opponentPlayer.getUsername(),
                readablePlayerState(opponentPlayer == null ? null : opponentPlayer.getStatus())
        ));
        roomConnectionView.setText(readableConnectionStatus(snapshot.connectionState()));
        String effectiveDifficulty = room == null ? selectedDifficulty : MultiplayerApi.fromRoomDifficulty(room.getDifficulty());
        selectedDifficulty = effectiveDifficulty;
        roomDifficultyValue.setText(readableDifficulty(effectiveDifficulty));

        boolean hasRoom = !snapshot.roomId().isEmpty();
        boolean isHost = isHost(snapshot);
        boolean selfReady = selfPlayer != null && selfPlayer.getStatus() == AircraftWar.PlayerStatus.PLAYER_STATUS_READY;
        boolean roomPlaying = room != null && room.getStatus() == AircraftWar.RoomStatus.ROOM_STATUS_PLAYING;
        boolean roomFinished = room != null && room.getStatus() == AircraftWar.RoomStatus.ROOM_STATUS_FINISHED;
        readyButton.setText(selfReady ? getString(R.string.multiplayer_ready_completed) : getString(R.string.multiplayer_ready_room));
        readyButton.setEnabled(hasRoom && !selfReady && !roomPlaying && !roomFinished);
        syncButton.setEnabled(hasRoom);
        startButton.setText(isHost ? getString(R.string.menu_start) : getString(R.string.multiplayer_waiting_host_start));
        startButton.setEnabled(hasRoom && isHost && room != null && room.getStatus() == AircraftWar.RoomStatus.ROOM_STATUS_READY);
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.6f);
        startButton.setTextColor(ContextCompat.getColor(this, startButton.isEnabled() ? R.color.ui2_button_text : R.color.ui2_muted));
        roomDifficultyEasy.setChecked(MenuActivity.DIFFICULTY_EASY.equals(effectiveDifficulty));
        roomDifficultyNormal.setChecked(MenuActivity.DIFFICULTY_NORMAL.equals(effectiveDifficulty));
        roomDifficultyHard.setChecked(MenuActivity.DIFFICULTY_HARD.equals(effectiveDifficulty));
        roomDifficultyEasy.setEnabled(isHost && !roomPlaying && !roomFinished);
        roomDifficultyNormal.setEnabled(isHost && !roomPlaying && !roomFinished);
        roomDifficultyHard.setEnabled(isHost && !roomPlaying && !roomFinished);
    }

    private AircraftWar.Player findPlayer(AircraftWar.Room room, String username) {
        if (room == null) {
            return null;
        }
        for (AircraftWar.Player player : room.getPlayersList()) {
            if (player.getUsername().equals(username)) {
                return player;
            }
        }
        return null;
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

    private AircraftWar.Player findHost(AircraftWar.Room room) {
        if (room == null) {
            return null;
        }
        for (AircraftWar.Player player : room.getPlayersList()) {
            if (player.getIsHost()) {
                return player;
            }
        }
        return null;
    }

    private boolean isHost(MultiplayerSession.Snapshot snapshot) {
        AircraftWar.Room room = snapshot.room();
        if (room == null) {
            return false;
        }
        for (AircraftWar.Player player : room.getPlayersList()) {
            if (player.getUsername().equals(snapshot.username())) {
                return player.getIsHost();
            }
        }
        return false;
    }

    private void bindBottomNav() {
        TextView homeLabel = findViewById(R.id.nav_home_label);
        TextView rankingLabel = findViewById(R.id.nav_ranking_label);

        homeLabel.setTextColor(ContextCompat.getColor(this, R.color.ui2_accent));
        rankingLabel.setTextColor(ContextCompat.getColor(this, R.color.ui2_body));

        findViewById(R.id.nav_home).setOnClickListener(v -> {
            MultiplayerSession.getInstance().clearRoomContext();
            LocalMultiplayerPrefs.clearRoomId(this);
            finishToMenu();
        });

        findViewById(R.id.nav_ranking).setOnClickListener(v -> {
            Intent leaderboardIntent = new Intent(this, LeaderboardActivity.class);
            startActivity(leaderboardIntent);
        });
    }

    private void readyRoom() {
        MultiplayerSession.Snapshot snapshot = MultiplayerSession.getInstance().snapshot();
        runRoomRequest(
                () -> multiplayerApi.readyRoom(snapshot.baseUrl(), snapshot.roomId(), snapshot.username()),
                response -> MultiplayerSession.getInstance().applyRoomState(response.getRoom(), java.util.List.of(), false, null)
        );
    }

    private void syncRoomState() {
        MultiplayerSession.Snapshot snapshot = MultiplayerSession.getInstance().snapshot();
        runRoomRequest(
                () -> multiplayerApi.getRoomState(snapshot.baseUrl(), snapshot.roomId(), snapshot.username()),
                response -> MultiplayerSession.getInstance().applyRoomState(
                        response.getRoom(),
                        response.getScoresList(),
                        response.getRoomFinished(),
                        response.hasResult() ? response.getResult() : null
                )
        );
    }

    private void startGame() {
        MultiplayerSession.Snapshot snapshot = MultiplayerSession.getInstance().snapshot();
        runRoomRequest(
                () -> multiplayerApi.startGame(snapshot.baseUrl(), snapshot.roomId(), snapshot.username()),
                response -> {
                    if (!response.getStarted()) {
                        toast(getString(R.string.multiplayer_error_start_not_ready));
                        return;
                    }
                    MultiplayerSession.getInstance().applyRoomState(response.getRoom(), java.util.List.of(), false, null);
                    if (!gameNavigated) {
                        gameNavigated = true;
                        Intent gameIntent = new Intent(this, GameActivity.class);
                        gameIntent.putExtra(EXTRA_DIFFICULTY, selectedDifficulty);
                        gameIntent.putExtra(GameActivity.EXTRA_IS_MULTIPLAYER, true);
                        startActivity(gameIntent);
                        finish();
                    }
                }
        );
    }

    private void updateRoomDifficulty(String difficulty) {
        MultiplayerSession.Snapshot snapshot = MultiplayerSession.getInstance().snapshot();
        runRoomRequest(
                () -> multiplayerApi.updateRoomDifficulty(snapshot.baseUrl(), snapshot.roomId(), snapshot.username(), difficulty),
                response -> MultiplayerSession.getInstance().applyRoomState(response.getRoom(), snapshot.scores(), false, null)
        );
    }

    private <T> void runRoomRequest(RoomCall<T> call, RoomResultHandler<T> onSuccess) {
        readyButton.setEnabled(false);
        syncButton.setEnabled(false);
        startButton.setEnabled(false);
        ioExecutor.execute(() -> {
            try {
                T result = call.call();
                UiExecutor.run(this, () -> {
                    onSuccess.handle(result);
                    renderSnapshot(MultiplayerSession.getInstance().snapshot());
                    toast(getString(R.string.multiplayer_action_success));
                });
            } catch (IOException e) {
                UiExecutor.run(this, () -> {
                    renderSnapshot(MultiplayerSession.getInstance().snapshot());
                    toast(e.getMessage() == null ? "网络请求失败" : e.getMessage());
                });
            }
        });
    }

    private String readableRoomStatus(AircraftWar.RoomStatus status) {
        if (status == null) {
            return getString(R.string.multiplayer_room_status_unknown);
        }
        return switch (status) {
            case ROOM_STATUS_WAITING -> getString(R.string.multiplayer_room_waiting);
            case ROOM_STATUS_FULL -> getString(R.string.multiplayer_room_full);
            case ROOM_STATUS_READY -> getString(R.string.multiplayer_room_ready);
            case ROOM_STATUS_PLAYING -> getString(R.string.multiplayer_room_playing);
            case ROOM_STATUS_FINISHED -> getString(R.string.multiplayer_room_finished);
            default -> getString(R.string.multiplayer_room_status_unknown);
        };
    }

    private String readableStageTitle(MultiplayerSession.Snapshot snapshot, AircraftWar.Player selfPlayer, AircraftWar.Player opponentPlayer) {
        AircraftWar.Room room = snapshot.room();
        if (room == null) {
            return getString(R.string.multiplayer_room_stage_unknown);
        }
        return switch (room.getStatus()) {
            case ROOM_STATUS_WAITING -> getString(R.string.multiplayer_room_stage_created);
            case ROOM_STATUS_FULL -> opponentPlayer == null
                    ? getString(R.string.multiplayer_room_stage_waiting_player)
                    : getString(R.string.multiplayer_room_stage_player_joined);
            case ROOM_STATUS_READY -> isHost(snapshot)
                    ? getString(R.string.multiplayer_room_stage_ready_host)
                    : getString(R.string.multiplayer_room_stage_ready_guest);
            case ROOM_STATUS_PLAYING -> getString(R.string.multiplayer_room_stage_playing);
            case ROOM_STATUS_FINISHED -> getString(R.string.multiplayer_room_stage_finished);
            default -> getString(R.string.multiplayer_room_stage_unknown);
        };
    }

    private String readableStageDetail(MultiplayerSession.Snapshot snapshot, AircraftWar.Player selfPlayer, AircraftWar.Player opponentPlayer) {
        AircraftWar.Room room = snapshot.room();
        if (room == null) {
            return getString(R.string.multiplayer_room_status_unknown);
        }
        if (room.getStatus() == AircraftWar.RoomStatus.ROOM_STATUS_WAITING) {
            return getString(R.string.multiplayer_room_stage_waiting_player);
        }
        if (room.getStatus() == AircraftWar.RoomStatus.ROOM_STATUS_FULL) {
            boolean selfReady = selfPlayer != null && selfPlayer.getStatus() == AircraftWar.PlayerStatus.PLAYER_STATUS_READY;
            boolean opponentReady = opponentPlayer != null && opponentPlayer.getStatus() == AircraftWar.PlayerStatus.PLAYER_STATUS_READY;
            if (selfReady && !opponentReady) {
                return getString(R.string.multiplayer_room_stage_self_ready);
            }
            if (!selfReady && opponentReady) {
                return getString(R.string.multiplayer_room_stage_opponent_ready);
            }
            return getString(R.string.multiplayer_room_stage_waiting_ready);
        }
        return readableRoomStatus(room.getStatus());
    }

    private String readablePlayerState(AircraftWar.PlayerStatus status) {
        if (status == null) {
            return getString(R.string.multiplayer_player_state_waiting);
        }
        return switch (status) {
            case PLAYER_STATUS_JOINED -> getString(R.string.multiplayer_player_state_joined);
            case PLAYER_STATUS_READY -> getString(R.string.multiplayer_player_state_ready);
            case PLAYER_STATUS_PLAYING -> getString(R.string.multiplayer_player_state_playing);
            case PLAYER_STATUS_FINISHED -> getString(R.string.multiplayer_player_state_finished);
            default -> getString(R.string.multiplayer_player_state_waiting);
        };
    }

    private String readableConnectionStatus(MultiplayerSession.ConnectionState state) {
        return switch (state) {
            case CONNECTED -> getString(R.string.multiplayer_connection_connected);
            case CONNECTING -> getString(R.string.multiplayer_connection_connecting);
            case ERROR -> getString(R.string.multiplayer_connection_error);
            default -> getString(R.string.multiplayer_connection_disconnected);
        };
    }

    private String readableDifficulty(String difficulty) {
        return switch (difficulty) {
            case MenuActivity.DIFFICULTY_EASY -> getString(R.string.difficulty_easy);
            case MenuActivity.DIFFICULTY_HARD -> getString(R.string.difficulty_hard);
            default -> getString(R.string.difficulty_normal);
        };
    }

    private void finishToMenu() {
        Intent intent = new Intent(this, MenuActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @FunctionalInterface
    private interface RoomCall<T> {
        T call() throws IOException;
    }

    @FunctionalInterface
    private interface RoomResultHandler<T> {
        void handle(T result);
    }
}
