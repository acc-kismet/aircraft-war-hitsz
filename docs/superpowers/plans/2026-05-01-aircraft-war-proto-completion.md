# Aircraft War Proto Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete `backend/aircraft_war.proto` so the Java client and Go backend can share one protobuf contract for HTTP and WebSocket multiplayer data exchange.

**Architecture:** Keep the protocol file focused on shared transport models only. Remove the unused RPC `service`, define a minimal set of enums and messages for the confirmed two-player business loop, and align field names with the approved Chinese design document.

**Tech Stack:** Protobuf proto3, Java client code generation, Go protobuf code generation, HTTP, WebSocket

---

## File Structure

- Modify: `backend/aircraft_war.proto`
  - Define enums for room status, player status, enemy type, and game result type
  - Define base entities shared by HTTP and WebSocket
  - Define HTTP request/response messages
  - Define WebSocket event/broadcast messages
- Reference: `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`
  - Source of truth for protocol scope and business semantics

### Task 1: Replace placeholder proto skeleton with shared enums and base messages

**Files:**
- Modify: `backend/aircraft_war.proto`
- Reference: `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`

- [ ] **Step 1: Inspect the current proto file and confirm it only contains placeholders**

Read `backend/aircraft_war.proto` and verify it currently contains:

```proto
syntax = "proto3";

package hitsz.aircraftwar.backend;

option go_package = "github.com/acc1111/aircraft-war-hitsz/backend/pb;pb";

service  AircraftWar{
}
```

Expected: the file does not yet define usable enums or messages.

- [ ] **Step 2: Remove the unused service and write shared enums**

Replace the placeholder structure with these enum definitions:

```proto
enum RoomStatus {
  ROOM_STATUS_UNSPECIFIED = 0;
  ROOM_STATUS_WAITING = 1;
  ROOM_STATUS_FULL = 2;
  ROOM_STATUS_READY = 3;
  ROOM_STATUS_PLAYING = 4;
  ROOM_STATUS_FINISHED = 5;
}

enum PlayerStatus {
  PLAYER_STATUS_UNSPECIFIED = 0;
  PLAYER_STATUS_JOINED = 1;
  PLAYER_STATUS_READY = 2;
  PLAYER_STATUS_PLAYING = 3;
  PLAYER_STATUS_FINISHED = 4;
}

enum EnemyType {
  ENEMY_TYPE_UNSPECIFIED = 0;
  ENEMY_TYPE_MOB = 1;
  ENEMY_TYPE_ELITE = 2;
  ENEMY_TYPE_BOSS = 3;
}

enum GameResultType {
  GAME_RESULT_UNSPECIFIED = 0;
  GAME_RESULT_WIN = 1;
  GAME_RESULT_LOSE = 2;
  GAME_RESULT_DRAW = 3;
}
```

- [ ] **Step 3: Write minimal shared base messages**

Add these shared messages below the enums:

```proto
message Player {
  string username = 1;
  PlayerStatus status = 2;
  bool is_host = 3;
}

message Room {
  string room_id = 1;
  RoomStatus status = 2;
  repeated Player players = 3;
}

message RoomPlayerScore {
  string username = 1;
  int32 score = 2;
  bool finished = 3;
}

message RoomResult {
  string room_id = 1;
  string player_a_username = 2;
  string player_b_username = 3;
  int32 player_a_score = 4;
  int32 player_b_score = 5;
  string winner_username = 6;
  GameResultType self_result = 7;
  int64 finished_at = 8;
}

message LeaderboardEntry {
  string username = 1;
  int32 best_score = 2;
  int32 win_count = 3;
  int32 game_count = 4;
  int64 updated_at = 5;
}
```

- [ ] **Step 4: Verify the base protocol now matches the design doc**

Check that the file now has:

- no `service` definition
- four enums
- five base messages
- field names matching the design document

Expected: the proto now represents the core room, player, score, result, and leaderboard structures.

### Task 2: Add HTTP request and response messages

**Files:**
- Modify: `backend/aircraft_war.proto`
- Reference: `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`

- [ ] **Step 1: Add room lifecycle HTTP requests**

Append these request messages:

```proto
message CreateRoomRequest {
  string username = 1;
}

message JoinRoomRequest {
  string room_id = 1;
  string username = 2;
}

message ReadyRoomRequest {
  string room_id = 1;
  string username = 2;
}

message StartGameRequest {
  string room_id = 1;
  string username = 2;
}
```

- [ ] **Step 2: Add room lifecycle HTTP responses**

Append these response messages:

```proto
message CreateRoomResponse {
  Room room = 1;
  Player self = 2;
}

message JoinRoomResponse {
  Room room = 1;
  Player self = 2;
}

message ReadyRoomResponse {
  Room room = 1;
}

message StartGameResponse {
  Room room = 1;
  bool started = 2;
}
```

- [ ] **Step 3: Add result and leaderboard HTTP messages**

Append these query messages:

```proto
message GetRoomResultRequest {
  string room_id = 1;
  string username = 2;
}

message GetRoomResultResponse {
  RoomResult result = 1;
}

message GetLeaderboardRequest {
  int32 limit = 1;
  int32 offset = 2;
}

message GetLeaderboardResponse {
  repeated LeaderboardEntry entries = 1;
}
```

- [ ] **Step 4: Verify HTTP coverage against the approved flow**

Check that the proto covers all approved HTTP actions:

- create room
- join room
- ready room
- start game
- query room result
- query leaderboard

Expected: every approved HTTP endpoint in the design doc has a request and response message pair.

### Task 3: Add WebSocket event and broadcast messages

**Files:**
- Modify: `backend/aircraft_war.proto`
- Reference: `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`

- [ ] **Step 1: Add client-to-server event messages**

Append these WebSocket upload messages:

```proto
message PlayerDefeatEvent {
  string room_id = 1;
  string username = 2;
  EnemyType enemy_type = 3;
  int32 score_delta = 4;
  string client_event_id = 5;
}

message PlayerGameOverEvent {
  string room_id = 1;
  string username = 2;
  int32 final_score = 3;
  string reason = 4;
}
```

- [ ] **Step 2: Add server-to-client broadcast messages**

Append these WebSocket broadcast messages:

```proto
message ScoreBroadcast {
  string room_id = 1;
  repeated RoomPlayerScore scores = 2;
  int64 updated_at = 3;
}

message GameFinishedBroadcast {
  string room_id = 1;
  bool finished = 2;
  RoomResult result = 3;
}
```

- [ ] **Step 3: Verify WebSocket coverage against the approved flow**

Check that the proto covers all approved WebSocket actions:

- defeat event upload
- game-over upload
- score broadcast
- finished broadcast

Expected: every approved WebSocket interaction in the design doc has a corresponding protobuf message.

### Task 4: Final consistency pass

**Files:**
- Modify: `backend/aircraft_war.proto`
- Reference: `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`

- [ ] **Step 1: Review package and go_package declarations**

Keep the proto header as:

```proto
syntax = "proto3";

package hitsz.aircraftwar.backend;

option go_package = "github.com/acc1111/aircraft-war-hitsz/backend/pb;pb";
```

Expected: package namespace remains stable for both Java and Go code generation.

- [ ] **Step 2: Review field naming and numbering for consistency**

Check that:

- field names use snake_case
- enum names use all-caps constants
- field numbers are unique within each message
- shared message names are reused instead of duplicated

Expected: the final proto is clean and ready for code generation.

- [ ] **Step 3: Confirm the completed proto is aligned with the Chinese protocol doc**

Verify the final file matches these sections in `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`:

- section 7 room and player states
- section 8 score rules
- section 9 result rules
- section 10 leaderboard rules
- section 11 HTTP contract
- section 12 WebSocket contract
- section 13 protobuf message set

Expected: no confirmed requirement from the doc is missing from the proto file.
