package com.airwar.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airwar.android.R;
import com.airwar.android.net.MultiplayerApi;
import com.airwar.android.net.NetworkConfig;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hitsz.aircraftwar.backend.AircraftWar;

public class LeaderboardActivity extends AppCompatActivity {
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final MultiplayerApi multiplayerApi = new MultiplayerApi();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_leaderboard);

        TextView title = findViewById(R.id.leaderboard_header);
        TextView emptyText = findViewById(R.id.leaderboard_empty_text);
        LinearLayout top3Container = findViewById(R.id.leaderboard_top3_container);
        LinearLayout listContainer = findViewById(R.id.leaderboard_list_container);
        Button backToMenuButton = findViewById(R.id.leaderboard_back_menu_button);
        title.setText(getString(R.string.leaderboard_title_global));
        bindBottomNav();

        backToMenuButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, MenuActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });

        loadLeaderboard(emptyText, top3Container, listContainer);
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void bindBottomNav() {
        TextView homeLabel = findViewById(R.id.nav_home_label);
        TextView rankingLabel = findViewById(R.id.nav_ranking_label);

        homeLabel.setTextColor(ContextCompat.getColor(this, R.color.ui2_body));
        rankingLabel.setTextColor(ContextCompat.getColor(this, R.color.ui2_accent));

        findViewById(R.id.nav_home).setOnClickListener(v -> {
            Intent intent = new Intent(this, MenuActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });

        findViewById(R.id.nav_ranking).setOnClickListener(v -> {
        });
    }

    private void loadLeaderboard(TextView emptyText, LinearLayout top3Container, LinearLayout listContainer) {
        String baseUrl = LocalMultiplayerPrefs.getBaseUrl(this);
        ioExecutor.execute(() -> {
            try {
                // 排行榜已切换为真实后端数据源，不再读取本地 CSV。
                AircraftWar.GetLeaderboardResponse response = multiplayerApi.getLeaderboard(
                        NetworkConfig.normalizeBaseUrl(baseUrl),
                        50,
                        0
                );
                UiExecutor.run(this, () -> renderLeaderboard(response.getEntriesList(), emptyText, top3Container, listContainer));
            } catch (IOException e) {
                UiExecutor.run(this, () -> {
                    emptyText.setVisibility(View.VISIBLE);
                    emptyText.setText(getString(R.string.leaderboard_load_failed));
                    top3Container.removeAllViews();
                    listContainer.removeAllViews();
                    Toast.makeText(this, e.getMessage() == null ? "排行榜加载失败" : e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void renderLeaderboard(
            List<AircraftWar.LeaderboardEntry> entries,
            TextView emptyText,
            LinearLayout top3Container,
            LinearLayout listContainer
    ) {
        if (entries.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            return;
        }

        emptyText.setVisibility(View.GONE);
        top3Container.removeAllViews();
        listContainer.removeAllViews();

        for (int i = 0; i < entries.size(); i++) {
            AircraftWar.LeaderboardEntry entry = entries.get(i);
            int rank = i + 1;
            if (rank <= 3) {
                top3Container.addView(createTopRankCard(rank, entry));
            } else {
                View row = getLayoutInflater().inflate(R.layout.comp_rank_row, listContainer, false);
                TextView rankView = row.findViewById(R.id.rank_index);
                ImageView avatarView = row.findViewById(R.id.rank_avatar);
                TextView nameView = row.findViewById(R.id.rank_name);
                TextView scoreView = row.findViewById(R.id.rank_score);

                rankView.setText(String.format(java.util.Locale.ROOT, "%02d", rank));
                avatarView.setImageResource(PilotAvatarRegistry.drawableFor(entry.getAvatarId()));
                nameView.setText(entry.getUsername());
                scoreView.setText(String.valueOf(entry.getBestScore()));
                listContainer.addView(row);
            }
        }
    }

    private View createTopRankCard(int rank, AircraftWar.LeaderboardEntry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackgroundResource(R.drawable.ui2_panel_glass);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        TextView rankView = new TextView(this);
        rankView.setText(String.valueOf(rank));
        rankView.setTextColor(ContextCompat.getColor(this, R.color.ui2_accent));
        rankView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        rankView.setTypeface(rankView.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams rankParams = new LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT);
        rankView.setLayoutParams(rankParams);

        ImageView avatar = new ImageView(this);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        avatarParams.setMarginEnd(dp(12));
        avatar.setLayoutParams(avatarParams);
        avatar.setBackgroundResource(R.drawable.ui2_avatar_border_selected);
        avatar.setPadding(dp(2), dp(2), dp(2), dp(2));
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageResource(PilotAvatarRegistry.drawableFor(entry.getAvatarId()));

        LinearLayout textWrap = new LinearLayout(this);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        textWrap.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(entry.getUsername());
        name.setTextColor(ContextCompat.getColor(this, R.color.ui2_title));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);

        TextView duration = new TextView(this);
        duration.setText(getString(R.string.leaderboard_win_count_format, entry.getWinCount(), entry.getGameCount()));
        duration.setTextColor(ContextCompat.getColor(this, R.color.ui2_muted));
        duration.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        textWrap.addView(name);
        textWrap.addView(duration);

        TextView scoreText = new TextView(this);
        scoreText.setText(String.valueOf(entry.getBestScore()));
        scoreText.setTextColor(ContextCompat.getColor(this, R.color.ui2_accent));
        scoreText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        scoreText.setTypeface(scoreText.getTypeface(), android.graphics.Typeface.BOLD);

        card.addView(rankView);
        card.addView(avatar);
        card.addView(textWrap);
        card.addView(scoreText);
        return card;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }
}
