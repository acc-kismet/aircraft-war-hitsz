package com.airwar.android.net;

import org.junit.Test;

import hitsz.aircraftwar.backend.AircraftWar;

import static org.junit.Assert.assertEquals;

public class MultiplayerDifficultyMappingTest {

    @Test
    public void fromRoomDifficultyUsesAuthoritativeRoomValue() {
        AircraftWar.Room room = AircraftWar.Room.newBuilder()
                .setDifficulty(AircraftWar.RoomDifficulty.ROOM_DIFFICULTY_HARD)
                .build();

        assertEquals("hard", MultiplayerApi.fromRoomDifficulty(room.getDifficulty()));
    }

    @Test
    public void unspecifiedRoomDifficultyFallsBackToNormal() {
        AircraftWar.Room room = AircraftWar.Room.getDefaultInstance();

        assertEquals("normal", MultiplayerApi.fromRoomDifficulty(room.getDifficulty()));
    }
}
