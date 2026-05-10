package com.airwar.android.net;

import org.junit.Test;

import hitsz.aircraftwar.backend.AircraftWar;

import static org.junit.Assert.assertEquals;

public class HeartbeatMessageFactoryTest {

    @Test
    public void createUsesConnectedIdentityValues() {
        AircraftWar.WsMessage message = HeartbeatMessageFactory.create("903064", "牢大", 123L, 7L);

        AircraftWar.PlayerHeartbeatEvent event = message.getPlayerHeartbeatEvent();
        assertEquals("903064", event.getRoomId());
        assertEquals("牢大", event.getUsername());
        assertEquals(123L, event.getClientSentAt());
        assertEquals(7L, event.getSequence());
    }
}
