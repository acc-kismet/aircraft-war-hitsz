package com.airwar.android.net;

import hitsz.aircraftwar.backend.AircraftWar;

final class HeartbeatMessageFactory {
    private HeartbeatMessageFactory() {
    }

    static AircraftWar.WsMessage create(String roomId, String username, long clientSentAt, long sequence) {
        AircraftWar.PlayerHeartbeatEvent event = AircraftWar.PlayerHeartbeatEvent.newBuilder()
                .setRoomId(roomId == null ? "" : roomId)
                .setUsername(username == null ? "" : username)
                .setClientSentAt(clientSentAt)
                .setSequence(sequence)
                .build();
        return AircraftWar.WsMessage.newBuilder()
                .setPlayerHeartbeatEvent(event)
                .build();
    }
}
