package com.airwar.android.net;

import java.io.IOException;

import hitsz.aircraftwar.backend.AircraftWar;

public class MultiplayerApi {
    private final ProtoHttpClient httpClient = new ProtoHttpClient();

    public static AircraftWar.RoomDifficulty toRoomDifficulty(String difficulty) {
        if (difficulty == null) {
            return AircraftWar.RoomDifficulty.ROOM_DIFFICULTY_NORMAL;
        }
        return switch (difficulty.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "easy" -> AircraftWar.RoomDifficulty.ROOM_DIFFICULTY_EASY;
            case "hard" -> AircraftWar.RoomDifficulty.ROOM_DIFFICULTY_HARD;
            default -> AircraftWar.RoomDifficulty.ROOM_DIFFICULTY_NORMAL;
        };
    }

    public static String fromRoomDifficulty(AircraftWar.RoomDifficulty difficulty) {
        if (difficulty == null) {
            return "normal";
        }
        return switch (difficulty) {
            case ROOM_DIFFICULTY_EASY -> "easy";
            case ROOM_DIFFICULTY_HARD -> "hard";
            default -> "normal";
        };
    }

    // 房间前置流程统一走 HTTP，便于客户端在进入实时对战前完成状态确认。
    public AircraftWar.CreateRoomResponse createRoom(String baseUrl, String username, String avatarId, String difficulty) throws IOException {
        AircraftWar.CreateRoomRequest request = AircraftWar.CreateRoomRequest.newBuilder()
                .setUsername(username)
                .setAvatarId(avatarId)
                .setDifficulty(toRoomDifficulty(difficulty))
                .build();
        return httpClient.post(baseUrl, "/rooms/create", request, AircraftWar.CreateRoomResponse.parser());
    }

    public AircraftWar.JoinRoomResponse joinRoom(String baseUrl, String roomId, String username, String avatarId) throws IOException {
        AircraftWar.JoinRoomRequest request = AircraftWar.JoinRoomRequest.newBuilder()
                .setRoomId(roomId)
                .setUsername(username)
                .setAvatarId(avatarId)
                .build();
        return httpClient.post(baseUrl, "/rooms/join", request, AircraftWar.JoinRoomResponse.parser());
    }

    public AircraftWar.ReadyRoomResponse readyRoom(String baseUrl, String roomId, String username) throws IOException {
        AircraftWar.ReadyRoomRequest request = AircraftWar.ReadyRoomRequest.newBuilder()
                .setRoomId(roomId)
                .setUsername(username)
                .build();
        return httpClient.post(baseUrl, "/rooms/ready", request, AircraftWar.ReadyRoomResponse.parser());
    }

    public AircraftWar.UpdateRoomDifficultyResponse updateRoomDifficulty(String baseUrl, String roomId, String username, String difficulty) throws IOException {
        AircraftWar.UpdateRoomDifficultyRequest request = AircraftWar.UpdateRoomDifficultyRequest.newBuilder()
                .setRoomId(roomId)
                .setUsername(username)
                .setDifficulty(toRoomDifficulty(difficulty))
                .build();
        return httpClient.post(baseUrl, "/rooms/difficulty", request, AircraftWar.UpdateRoomDifficultyResponse.parser());
    }

    public AircraftWar.StartGameResponse startGame(String baseUrl, String roomId, String username) throws IOException {
        AircraftWar.StartGameRequest request = AircraftWar.StartGameRequest.newBuilder()
                .setRoomId(roomId)
                .setUsername(username)
                .build();
        return httpClient.post(baseUrl, "/rooms/start", request, AircraftWar.StartGameResponse.parser());
    }

    public AircraftWar.GetRoomStateResponse getRoomState(String baseUrl, String roomId, String username) throws IOException {
        AircraftWar.GetRoomStateRequest request = AircraftWar.GetRoomStateRequest.newBuilder()
                .setRoomId(roomId)
                .setUsername(username)
                .build();
        return httpClient.post(baseUrl, "/rooms/state", request, AircraftWar.GetRoomStateResponse.parser());
    }

    public AircraftWar.GetRoomResultResponse getRoomResult(String baseUrl, String roomId, String username) throws IOException {
        AircraftWar.GetRoomResultRequest request = AircraftWar.GetRoomResultRequest.newBuilder()
                .setRoomId(roomId)
                .setUsername(username)
                .build();
        return httpClient.post(baseUrl, "/rooms/result", request, AircraftWar.GetRoomResultResponse.parser());
    }

    public AircraftWar.GetLeaderboardResponse getLeaderboard(String baseUrl, int limit, int offset) throws IOException {
        AircraftWar.GetLeaderboardRequest request = AircraftWar.GetLeaderboardRequest.newBuilder()
                .setLimit(limit)
                .setOffset(offset)
                .build();
        return httpClient.post(baseUrl, "/leaderboard", request, AircraftWar.GetLeaderboardResponse.parser());
    }
}
