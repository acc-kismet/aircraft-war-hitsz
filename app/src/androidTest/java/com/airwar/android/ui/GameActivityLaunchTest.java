package com.airwar.android.ui;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.airwar.android.R;
import com.airwar.android.net.MultiplayerSession;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import hitsz.aircraftwar.backend.AircraftWar;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class GameActivityLaunchTest {

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        LocalMultiplayerPrefs.saveBaseConfig(context, "http://10.0.2.2:8080", "alice", PilotAvatarRegistry.IDS[0]);
        LocalMultiplayerPrefs.saveRoomId(context, "123456");
        MultiplayerSession.getInstance().configure("http://10.0.2.2:8080", "123456", "alice", PilotAvatarRegistry.IDS[0]);
        MultiplayerSession.getInstance().applyRoomState(
                AircraftWar.Room.newBuilder()
                        .setRoomId("123456")
                        .setStatus(AircraftWar.RoomStatus.ROOM_STATUS_PLAYING)
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("alice").setIsHost(true).build())
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("bob").build())
                        .build(),
                java.util.List.of(
                        AircraftWar.RoomPlayerScore.newBuilder().setUsername("alice").setScore(120).build(),
                        AircraftWar.RoomPlayerScore.newBuilder().setUsername("bob").setScore(80).build()
                ),
                false,
                null
        );
    }

    @Rule
    public ActivityScenarioRule<GameActivity> activityRule =
            new ActivityScenarioRule<>(GameActivity.class);

    @Test
    public void gameSurface_isDisplayed() {
        onView(withId(R.id.game_surface)).check(matches(isDisplayed()));
        onView(withId(R.id.hud_multiplayer_self_score)).check(matches(withText("alice 120")));
        onView(withId(R.id.hud_multiplayer_opponent_score)).check(matches(withText("bob 80")));
    }
}
