package com.airwar.android.ui;

import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.airwar.android.R;
import com.airwar.android.net.MultiplayerSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import hitsz.aircraftwar.backend.AircraftWar;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.intent.Intents.init;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.release;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class RoomActivityLaunchTest {

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        LocalMultiplayerPrefs.saveBaseConfig(context, "http://10.0.2.2:8080", "alice", PilotAvatarRegistry.IDS[0]);
        LocalMultiplayerPrefs.saveRoomId(context, "123456");
        MultiplayerSession.getInstance().configure("http://10.0.2.2:8080", "123456", "alice", PilotAvatarRegistry.IDS[0]);
        MultiplayerSession.getInstance().applyRoomState(
                AircraftWar.Room.newBuilder()
                        .setRoomId("123456")
                        .setStatus(AircraftWar.RoomStatus.ROOM_STATUS_WAITING)
                        .setDifficulty(AircraftWar.RoomDifficulty.ROOM_DIFFICULTY_HARD)
                        .build(),
                java.util.List.of(),
                false,
                null
        );
    }

    @After
    public void tearDown() {
        Context context = ApplicationProvider.getApplicationContext();
        MultiplayerSession.getInstance().clearRoomContext();
        LocalMultiplayerPrefs.clearRoomId(context);
        release();
    }

    @Test
    public void launchesAndShowsRoomCode() {
        init();
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, RoomActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<RoomActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withId(R.id.room_header)).check(matches(withText(R.string.multiplayer_room_title)));
            onView(withId(R.id.room_code_value)).check(matches(withText("123456")));
            onView(withId(R.id.room_status_value)).check(matches(withText(R.string.multiplayer_room_stage_created)));
            onView(withId(R.id.room_status_detail_value)).check(matches(withText(R.string.multiplayer_room_stage_waiting_player)));
            onView(withId(R.id.button_room_ready)).check(matches(isDisplayed()));
            onView(withId(R.id.room_owner_value)).check(matches(withText("-")));
            onView(withId(R.id.room_difficulty_value)).check(matches(withText(R.string.difficulty_hard)));
            onView(withId(R.id.button_room_start)).check(matches(isNotEnabled()));
        }
    }

    @Test
    public void pushedRoomStateShowsActualHostAndReadyStartState() {
        init();
        Context context = ApplicationProvider.getApplicationContext();
        MultiplayerSession.getInstance().applyRoomState(
                AircraftWar.Room.newBuilder()
                        .setRoomId("123456")
                        .setStatus(AircraftWar.RoomStatus.ROOM_STATUS_READY)
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("alice").setStatus(AircraftWar.PlayerStatus.PLAYER_STATUS_READY).build())
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("bob").setIsHost(true).setStatus(AircraftWar.PlayerStatus.PLAYER_STATUS_READY).build())
                        .build(),
                java.util.List.of(),
                false,
                null
        );
        Intent intent = new Intent(context, RoomActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<RoomActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withId(R.id.room_owner_value)).check(matches(withText("bob")));
            onView(withId(R.id.button_room_start)).check(matches(withText(R.string.multiplayer_waiting_host_start)));
            onView(withId(R.id.button_room_start)).check(matches(isNotEnabled()));
        }
    }

    @Test
    public void hostCanStartWhenPushedRoomStateBecomesReady() {
        init();
        Context context = ApplicationProvider.getApplicationContext();
        MultiplayerSession.getInstance().applyRoomState(
                AircraftWar.Room.newBuilder()
                        .setRoomId("123456")
                        .setStatus(AircraftWar.RoomStatus.ROOM_STATUS_READY)
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("alice").setIsHost(true).setStatus(AircraftWar.PlayerStatus.PLAYER_STATUS_READY).build())
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("bob").setStatus(AircraftWar.PlayerStatus.PLAYER_STATUS_READY).build())
                        .build(),
                java.util.List.of(),
                false,
                null
        );
        Intent intent = new Intent(context, RoomActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<RoomActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withId(R.id.button_room_start)).check(matches(isEnabled()));
            onView(withId(R.id.button_room_start)).check(matches(withText(R.string.menu_start)));
        }
    }

    @Test
    public void playingStateNavigatesToGameActivity() {
        init();
        Context context = ApplicationProvider.getApplicationContext();
        MultiplayerSession.getInstance().applyRoomState(
                AircraftWar.Room.newBuilder()
                        .setRoomId("123456")
                        .setStatus(AircraftWar.RoomStatus.ROOM_STATUS_READY)
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("alice").build())
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("bob").build())
                        .build(),
                java.util.List.of(
                        AircraftWar.RoomPlayerScore.newBuilder()
                                .setUsername("alice")
                                .setStatus(AircraftWar.PlayerStatus.PLAYER_STATUS_PLAYING)
                                .build()
                ),
                false,
                null
        );
        Intent intent = new Intent(context, RoomActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<RoomActivity> ignored = ActivityScenario.launch(intent)) {
            intended(hasComponent(GameActivity.class.getName()));
        }
    }
}
