# Aircraft War Room Sync Avatar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build server-pushed room state, pre-match avatar identity, persisted leaderboard avatars, and live opponent score display for multiplayer.

**Architecture:** Update the shared protobuf contract first on `feature/network` so both runtime stacks use the same wire format and data model. Then implement backend room-state push plus avatar persistence on `network-backend`, and Android identity, room, game HUD, and leaderboard changes on `network-frontend`, keeping `MultiplayerSession` as the single client-side state source.

**Tech Stack:** Protobuf proto3, Go, Gorilla WebSocket, SQLite via `modernc.org/sqlite`, Android Java, protobuf-javalite, OkHttp, Espresso/JUnit, Gradle.

---

## File Structure

- Create: `docs/superpowers/plans/2026-05-01-aircraft-war-room-sync-avatar-implementation.md`
- Modify: `proto/aircraft_war.proto`
- Modify: `backend/proto/aircraft_war.pb.go`
- Modify: `app/src/main/java/hitsz/aircraftwar/backend/AircraftWar.java`
- Modify: `backend/internal/app/server.go`
- Modify: `backend/tests/integration_test.go`
- Modify: `app/src/main/java/com/airwar/android/net/MultiplayerApi.java`
- Modify: `app/src/main/java/com/airwar/android/net/MultiplayerSession.java`
- Modify: `app/src/main/java/com/airwar/android/ui/LocalMultiplayerPrefs.java`
- Modify: `app/src/main/java/com/airwar/android/ui/MenuActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/RoomActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/GameActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/GameOverActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/LeaderboardActivity.java`
- Modify: `app/src/main/res/layout/activity_menu.xml`
- Modify: `app/src/main/res/layout/activity_room.xml`
- Modify: `app/src/main/res/layout/activity_game.xml`
- Modify: `app/src/main/res/layout/activity_game_over.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: relevant Android tests under `app/src/androidTest/java/com/airwar/android/ui/`

### Task 1: Update shared protobuf contract and regenerate code on `feature/network`

**Files:**
- Modify: `proto/aircraft_war.proto`
- Modify: `backend/proto/aircraft_war.pb.go`
- Modify: `app/src/main/java/hitsz/aircraftwar/backend/AircraftWar.java`

- [ ] **Step 1: Add the failing contract expectation by editing the proto**

Update `proto/aircraft_war.proto` to add:

- `string avatar_id` to `Player`
- `string avatar_id` to `LeaderboardEntry`
- `string avatar_id` to `CreateRoomRequest`
- `string avatar_id` to `JoinRoomRequest`
- new `RoomStateBroadcast` message with `room`, `scores`, `room_finished`, `result`, `updated_at`
- new `room_state_broadcast` branch in `WsMessage.oneof`

- [ ] **Step 2: Regenerate protobuf outputs and verify missing generated members exist**

Run:
`protoc --go_out=backend --java_out=app/src/main/java proto/aircraft_war.proto`

Expected: generated Go and Java files now expose getters/builders for `avatar_id` and `RoomStateBroadcast`.

- [ ] **Step 3: Run a narrow compile verification before parallel work starts**

Run:
`go test ./backend/... -run TestDoesNotExist -count=1`

Expected: package compile succeeds or surfaces exact compile breakage caused by the contract update.

### Task 2: Implement backend room-state push and avatar persistence on `network-backend`

**Files:**
- Modify: `backend/internal/app/server.go`
- Modify: `backend/tests/integration_test.go`
- Modify: `backend/proto/aircraft_war.pb.go`

- [ ] **Step 1: Write failing backend tests for the new behavior**

Extend `backend/tests/integration_test.go` with tests that assert:

- joining a room triggers a pushed `RoomStateBroadcast` to an already connected host
- ready actions trigger pushed `RoomStateBroadcast` updates to connected players
- a fresh WebSocket connection receives an immediate `RoomStateBroadcast`
- leaderboard responses include persisted `avatar_id`

- [ ] **Step 2: Run the targeted backend tests to verify RED**

Run:
`go test ./backend/tests -run 'TestRoomStateBroadcastOnJoinAndReady|TestWebSocketSendsInitialRoomState|TestLeaderboardPersistsAvatar' -count=1`

Expected: FAIL because the server does not yet broadcast full room snapshots or persist avatar ids.

- [ ] **Step 3: Implement minimal backend changes**

Update `backend/internal/app/server.go` so that it:

- stores avatar id in in-memory player state and protobuf player snapshots
- migrates leaderboard schema to include `avatar_id`
- upserts leaderboard avatar ids with score progress
- sends `RoomStateBroadcast` on create, join, ready, start, player finish, room finish, and initial WebSocket connect
- keeps `ScoreBroadcast` for live score deltas during play

- [ ] **Step 4: Re-run the targeted backend tests to verify GREEN**

Run:
`go test ./backend/tests -run 'TestRoomStateBroadcastOnJoinAndReady|TestWebSocketSendsInitialRoomState|TestLeaderboardPersistsAvatar' -count=1`

Expected: PASS.

- [ ] **Step 5: Run full backend verification**

Run:
`go test ./... -count=1`

Expected: PASS.

### Task 3: Implement Android multiplayer identity and room push handling on `network-frontend`

**Files:**
- Modify: `app/src/main/java/com/airwar/android/net/MultiplayerApi.java`
- Modify: `app/src/main/java/com/airwar/android/net/MultiplayerSession.java`
- Modify: `app/src/main/java/com/airwar/android/ui/LocalMultiplayerPrefs.java`
- Modify: `app/src/main/java/com/airwar/android/ui/MenuActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/RoomActivity.java`
- Modify: `app/src/main/res/layout/activity_menu.xml`
- Modify: `app/src/main/res/layout/activity_room.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/com/airwar/android/ui/RoomActivityLaunchTest.java`

- [ ] **Step 1: Add or tighten Android room-state tests first**

Update or add Android tests so they cover:

- `RoomActivity` renders pushed room-state changes without requiring manual sync
- start button disabled state uses the intended disabled presentation
- `MenuActivity` can restore and forward avatar identity through local prefs/session setup

- [ ] **Step 2: Run the narrow Android compile/test command to verify RED**

Run:
`./gradlew app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: FAIL because the app code does not yet reference the new proto fields or room-state message handling correctly.

- [ ] **Step 3: Implement minimal Android room/identity changes**

Update the client so that it:

- saves and restores avatar id in `LocalMultiplayerPrefs`
- lets `MenuActivity` choose avatar before create/join and sends it through `MultiplayerApi`
- teaches `MultiplayerSession` to process `RoomStateBroadcast`
- makes `RoomActivity` render actual host identity, rely on pushed room state, and keep sync as fallback only
- updates the room start button disabled text/color to a visible light-gray state

- [ ] **Step 4: Re-run the compile/test command to verify GREEN**

Run:
`./gradlew app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: PASS.

### Task 4: Implement Android in-game scoreboard, end-screen cleanup, and leaderboard avatars on `network-frontend`

**Files:**
- Modify: `app/src/main/java/com/airwar/android/ui/GameActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/GameOverActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/LeaderboardActivity.java`
- Modify: `app/src/main/res/layout/activity_game.xml`
- Modify: `app/src/main/res/layout/activity_game_over.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/com/airwar/android/ui/GameActivityLaunchTest.java`
- Test: `app/src/androidTest/java/com/airwar/android/ui/RoomActivityLaunchTest.java`

- [ ] **Step 1: Add failing expectations for the gameplay and leaderboard UI**

Update or add tests that assert:

- `GameActivity` exposes self and opponent score labels in multiplayer mode
- `GameOverActivity` no longer contains avatar-selection UI controls
- `LeaderboardActivity` uses server-provided avatar ids instead of always using the default avatar

- [ ] **Step 2: Run the narrow Android compile/test command to verify RED**

Run:
`./gradlew app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: FAIL because the HUD, game-over layout, and leaderboard binding do not yet match the new behavior.

- [ ] **Step 3: Implement minimal gameplay/result/leaderboard changes**

Update the frontend so that it:

- adds a lightweight multiplayer score panel to `GameActivity`
- binds live score updates from `MultiplayerSession`
- removes avatar selection state and layout from `GameOverActivity`
- maps `LeaderboardEntry.avatar_id` through `PilotAvatarRegistry`

- [ ] **Step 4: Re-run the compile/test command to verify GREEN**

Run:
`./gradlew app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: PASS.

### Task 5: Integrate and verify on `feature/network`

**Files:**
- Verify: `proto/aircraft_war.proto`
- Verify: `backend/...`
- Verify: `app/...`

- [ ] **Step 1: Merge validated backend and frontend branches back into `feature/network`**

Run:
`git merge network-backend && git merge network-frontend`

Expected: merges apply cleanly or produce only expected conflict resolution around shared generated files and proto outputs.

- [ ] **Step 2: Run backend verification in the root branch**

Run:
`go test ./... -count=1`

Expected: PASS.

- [ ] **Step 3: Run Android verification in the root branch**

Run:
`./gradlew app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: PASS.

- [ ] **Step 4: Review final diff scope**

Run:
`git diff -- proto/aircraft_war.proto backend app docs/superpowers/specs/2026-05-01-aircraft-war-room-sync-avatar-design.md docs/superpowers/plans/2026-05-01-aircraft-war-room-sync-avatar-implementation.md`

Expected: only protocol, backend multiplayer, Android multiplayer UI/state, and the approved docs are changed.
