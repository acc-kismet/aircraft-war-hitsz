package com.airwar.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airwar.android.R;
import com.airwar.android.net.MultiplayerApi;
import com.airwar.android.net.MultiplayerSession;
import com.airwar.android.net.NetworkConfig;
import com.airwar.core.difficulty.DifficultyConfig;
import com.airwar.core.difficulty.DifficultyLevel;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hitsz.aircraftwar.backend.AircraftWar;

public class MenuActivity extends AppCompatActivity {

    public static final String EXTRA_DIFFICULTY = "com.airwar.android.extra.DIFFICULTY";
    public static final String DIFFICULTY_EASY = "easy";
    public static final String DIFFICULTY_NORMAL = "normal";
    public static final String DIFFICULTY_HARD = "hard";

    private String selectedDifficulty = DIFFICULTY_NORMAL;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final MultiplayerApi multiplayerApi = new MultiplayerApi();
    private TextView balanceTable;
    private ImageView difficultyPreview;
    private TextView roomStatusView;
    private EditText baseUrlInput;
    private EditText usernameInput;
    private EditText roomIdInput;
    private Button createRoomButton;
    private Button joinRoomButton;
    private LinearLayout avatarContainer;
    private String selectedAvatarId = PilotAvatarRegistry.DEFAULT_AVATAR_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);

        Button easyButton = findViewById(R.id.button_difficulty_easy);
        Button normalButton = findViewById(R.id.button_difficulty_normal);
        Button hardButton = findViewById(R.id.button_difficulty_hard);
        Button startButton = findViewById(R.id.button_start);
        ToggleButton soundToggle = findViewById(R.id.button_sound_toggle);
        createRoomButton = findViewById(R.id.button_create_room);
        joinRoomButton = findViewById(R.id.button_join_room);
        Button readyRoomButton = findViewById(R.id.button_ready_room);
        Button syncRoomStateButton = findViewById(R.id.button_sync_room_state);
        balanceTable = findViewById(R.id.text_balance_table);
        difficultyPreview = findViewById(R.id.image_difficulty_preview);
        roomStatusView = findViewById(R.id.text_room_status);
        baseUrlInput = findViewById(R.id.input_base_url);
        usernameInput = findViewById(R.id.input_username);
        roomIdInput = findViewById(R.id.input_room_id);
        avatarContainer = findViewById(R.id.menu_avatar_container);
        TextView headerTitle = findViewById(R.id.menu_header);

        headerTitle.setText(getString(R.string.menu_title));
        bindBottomNav();
        buildAvatarSelector();
        bindSavedMultiplayerState();
        soundToggle.setChecked(LocalMultiplayerPrefs.isSoundEnabled(this));
        soundToggle.setOnCheckedChangeListener((buttonView, isChecked) -> LocalMultiplayerPrefs.saveSoundEnabled(this, isChecked));

        easyButton.setOnClickListener(v -> {
            selectedDifficulty = DIFFICULTY_EASY;
            updateDifficultySelection(easyButton, normalButton, hardButton, DifficultyLevel.EASY);
        });

        normalButton.setOnClickListener(v -> {
            selectedDifficulty = DIFFICULTY_NORMAL;
            updateDifficultySelection(normalButton, easyButton, hardButton, DifficultyLevel.NORMAL);
        });

        hardButton.setOnClickListener(v -> {
            selectedDifficulty = DIFFICULTY_HARD;
            updateDifficultySelection(hardButton, easyButton, normalButton, DifficultyLevel.HARD);
        });

        updateDifficultySelection(normalButton, easyButton, hardButton, DifficultyLevel.NORMAL);

        createRoomButton.setOnClickListener(v -> createRoom());
        joinRoomButton.setOnClickListener(v -> joinRoom());
        readyRoomButton.setVisibility(android.view.View.GONE);
        syncRoomStateButton.setVisibility(android.view.View.GONE);
        startButton.setOnClickListener(v -> startSinglePlayer());
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void bindSavedMultiplayerState() {
        baseUrlInput.setText(LocalMultiplayerPrefs.getBaseUrl(this));
        usernameInput.setText(LocalMultiplayerPrefs.getUsername(this));
        roomIdInput.setText(LocalMultiplayerPrefs.getRoomId(this));
        selectedAvatarId = LocalMultiplayerPrefs.getAvatarId(this);
        refreshAvatarSelection();
        MultiplayerSession.Snapshot snapshot = MultiplayerSession.getInstance().snapshot();
        updateRoomStatusText(snapshot.room(), normalizedRoomId(), normalizedUsername());
    }

    private void buildAvatarSelector() {
        avatarContainer.removeAllViews();
        for (String avatarId : PilotAvatarRegistry.IDS) {
            ImageView imageView = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(52));
            params.setMarginEnd(dp(10));
            imageView.setLayoutParams(params);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setBackgroundResource(R.drawable.ui2_avatar_border);
            imageView.setImageResource(PilotAvatarRegistry.drawableFor(avatarId));
            imageView.setTag(avatarId);
            imageView.setPadding(dp(2), dp(2), dp(2), dp(2));
            imageView.setOnClickListener(v -> {
                selectedAvatarId = avatarId;
                LocalMultiplayerPrefs.saveAvatarId(this, avatarId);
                refreshAvatarSelection();
            });
            avatarContainer.addView(imageView);
        }
    }

    private void refreshAvatarSelection() {
        if (avatarContainer == null) {
            return;
        }
        for (int i = 0; i < avatarContainer.getChildCount(); i++) {
            ImageView imageView = (ImageView) avatarContainer.getChildAt(i);
            String avatarId = String.valueOf(imageView.getTag());
            imageView.setBackgroundResource(
                    avatarId.equals(selectedAvatarId)
                            ? R.drawable.ui2_avatar_border_selected
                            : R.drawable.ui2_avatar_border
            );
        }
    }

    private void updateDifficultySelection(Button selected, Button otherOne, Button otherTwo, DifficultyLevel level) {
        selected.setSelected(true);
        otherOne.setSelected(false);
        otherTwo.setSelected(false);

        DifficultyConfig cfg = DifficultyConfig.of(level);
        int previewRes = switch (level) {
            case EASY -> R.drawable.bg2;
            case NORMAL -> R.drawable.bg3;
            case HARD -> R.drawable.bg5;
        };
        difficultyPreview.setImageResource(previewRes);

        balanceTable.setText(getString(
                R.string.menu_balance_template,
                cfg.enemySpawnIntervalMs(),
                cfg.enemyShootIntervalMs(),
                cfg.mobEnemyHp(),
                cfg.enemyBulletDamage(),
                cfg.enemyCollisionDamage(),
                cfg.propDropChancePercent()
        ));
    }

    private void bindBottomNav() {
        TextView homeLabel = findViewById(R.id.nav_home_label);
        TextView rankingLabel = findViewById(R.id.nav_ranking_label);

        homeLabel.setTextColor(ContextCompat.getColor(this, R.color.ui2_accent));
        rankingLabel.setTextColor(ContextCompat.getColor(this, R.color.ui2_body));

        findViewById(R.id.nav_home).setOnClickListener(v -> {
        });

        findViewById(R.id.nav_ranking).setOnClickListener(v -> {
            Intent leaderboardIntent = new Intent(this, LeaderboardActivity.class);
            startActivity(leaderboardIntent);
        });
    }

    // 房间创建、加入、准备、状态同步都走真实 HTTP 接口，避免页面层出现和协议脱节的假流程。
    private void createRoom() {
        String baseUrl = normalizedBaseUrl();
        String username = normalizedUsername();
        if (!validateBaseUrl(baseUrl) || !validateUsername(username)) {
            return;
        }
        LocalMultiplayerPrefs.saveBaseConfig(this, baseUrl, username, selectedAvatarId);
        setRoomEntryButtonsEnabled(false);
        runRoomRequest(() -> multiplayerApi.createRoom(baseUrl, username, selectedAvatarId, selectedDifficulty), response -> {
            roomIdInput.setText(response.getRoom().getRoomId());
            LocalMultiplayerPrefs.saveRoomId(this, response.getRoom().getRoomId());
            MultiplayerSession.getInstance().configure(baseUrl, response.getRoom().getRoomId(), username, selectedAvatarId);
            MultiplayerSession.getInstance().applyRoomState(response.getRoom(), java.util.List.of(), false, null);
            updateRoomStatusText(response.getRoom(), response.getRoom().getRoomId(), username);
            openRoomView();
        });
    }

    private void joinRoom() {
        String baseUrl = normalizedBaseUrl();
        String username = normalizedUsername();
        String roomId = normalizedRoomId();
        if (!validateBaseUrl(baseUrl) || !validateUsername(username) || !validateRoomId(roomId)) {
            return;
        }
        LocalMultiplayerPrefs.saveBaseConfig(this, baseUrl, username, selectedAvatarId);
        LocalMultiplayerPrefs.saveRoomId(this, roomId);
        setRoomEntryButtonsEnabled(false);
        runRoomRequest(() -> multiplayerApi.joinRoom(baseUrl, roomId, username, selectedAvatarId), response -> {
            MultiplayerSession.getInstance().configure(baseUrl, roomId, username, selectedAvatarId);
            MultiplayerSession.getInstance().applyRoomState(response.getRoom(), java.util.List.of(), false, null);
            updateRoomStatusText(response.getRoom(), roomId, username);
            openRoomView();
        });
    }

    private <T> void runRoomRequest(RoomCall<T> call, RoomResultHandler<T> onSuccess) {
        ioExecutor.execute(() -> {
            try {
                T result = call.call();
                UiExecutor.run(this, () -> {
                    onSuccess.handle(result);
                    toast(getString(R.string.multiplayer_action_success));
                });
            } catch (IOException e) {
                UiExecutor.run(this, () -> {
                    setRoomEntryButtonsEnabled(true);
                    toast(e.getMessage() == null ? "网络请求失败" : e.getMessage());
                });
            }
        });
    }

    private void setRoomEntryButtonsEnabled(boolean enabled) {
        createRoomButton.setEnabled(enabled);
        joinRoomButton.setEnabled(enabled);
    }

    private void openRoomView() {
        Intent roomIntent = new Intent(this, RoomActivity.class);
        roomIntent.putExtra(RoomActivity.EXTRA_DIFFICULTY, selectedDifficulty);
        startActivity(roomIntent);
        finish();
    }

    private void startSinglePlayer() {
        Intent gameIntent = new Intent(this, GameActivity.class);
        gameIntent.putExtra(GameActivity.EXTRA_DIFFICULTY, selectedDifficulty);
        gameIntent.putExtra(GameActivity.EXTRA_IS_MULTIPLAYER, false);
        startActivity(gameIntent);
    }

    private void updateRoomStatusText(AircraftWar.Room room, String roomId, String username) {
        String roomStatus = readableRoomStatus(room == null ? null : room.getStatus());
        roomStatusView.setText(getString(
                R.string.multiplayer_room_status_value,
                roomId == null || roomId.isEmpty() ? "-" : roomId,
                username == null || username.isEmpty() ? "-" : username,
                roomStatus
        ));
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

    private boolean validateBaseUrl(String baseUrl) {
        if (baseUrl.isEmpty()) {
            toast(getString(R.string.multiplayer_error_missing_base_url));
            return false;
        }
        return true;
    }

    private boolean validateUsername(String username) {
        if (username.isEmpty()) {
            toast(getString(R.string.multiplayer_error_missing_username));
            return false;
        }
        return true;
    }

    private boolean validateRoomId(String roomId) {
        if (roomId.isEmpty()) {
            toast(getString(R.string.multiplayer_error_missing_room_id));
            return false;
        }
        if (!roomId.matches("\\d{6}")) {
            toast(getString(R.string.multiplayer_error_room_id_format));
            return false;
        }
        return true;
    }

    private String normalizedBaseUrl() {
        CharSequence text = baseUrlInput.getText();
        return NetworkConfig.normalizeBaseUrl(text == null ? "" : text.toString());
    }

    private String normalizedUsername() {
        CharSequence text = usernameInput.getText();
        return text == null ? "" : text.toString().trim();
    }

    private String normalizedRoomId() {
        CharSequence text = roomIdInput.getText();
        return text == null ? "" : text.toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
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
