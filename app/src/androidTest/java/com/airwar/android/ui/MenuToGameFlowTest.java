package com.airwar.android.ui;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
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
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.init;
import static androidx.test.espresso.intent.Intents.release;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

@RunWith(AndroidJUnit4.class)
public class MenuToGameFlowTest {

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        LocalMultiplayerPrefs.saveBaseConfig(context, "http://10.0.2.2:8080", "alice", PilotAvatarRegistry.IDS[0]);
        LocalMultiplayerPrefs.saveRoomId(context, "123456");
        MultiplayerSession.getInstance().configure("http://10.0.2.2:8080", "123456", "alice", PilotAvatarRegistry.IDS[0]);
        MultiplayerSession.getInstance().applyRoomState(
                AircraftWar.Room.newBuilder()
                        .setRoomId("123456")
                        .setStatus(AircraftWar.RoomStatus.ROOM_STATUS_READY)
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("alice").setIsHost(true).build())
                        .addPlayers(AircraftWar.Player.newBuilder().setUsername("bob").build())
                        .build(),
                java.util.List.of(),
                false,
                null
        );
        init();
    }

    @After
    public void tearDown() {
        Context context = ApplicationProvider.getApplicationContext();
        MultiplayerSession.getInstance().clearRoomContext();
        LocalMultiplayerPrefs.clearRoomId(context);
        release();
    }

    @Test
    public void clickingStart_navigatesToGameActivity() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, RoomActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<RoomActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withId(R.id.button_room_start)).perform(click());
            intended(hasComponent(GameActivity.class.getName()));
        }
    }
}
