# Aircraft War Difficulty Audio Score Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add shared multiplayer room difficulty, unify local scoring with backend rules, restore single-player replay flow, add persisted audio toggles, and tighten multiplayer final-result delivery.

**Architecture:** Extend the shared proto and backend room state so multiplayer difficulty becomes room-authoritative and broadcast to both players. On Android, make `GameActivity` explicitly mode-aware for score source and end-screen behavior, introduce a shared local audio preference, and route both single-player and multiplayer to the correct replay/result flow. Keep changes minimal by reusing `RoomStateBroadcast`, `GameFinishedBroadcast`, existing room/session state, and current HUD/layout structure.

**Tech Stack:** Protobuf proto3, Go, Gorilla WebSocket, SQLite via `modernc.org/sqlite`, Android Java, protobuf-javalite, Gradle, game-core Java engine, Espresso/JUnit.

---

## File Structure

- Create: `docs/superpowers/plans/2026-05-01-aircraft-war-difficulty-audio-score-implementation.md`
- Modify: `proto/aircraft_war.proto`
- Modify: `backend/proto/aircraft_war.pb.go`
- Modify: `app/src/main/java/hitsz/aircraftwar/backend/AircraftWar.java`
- Modify: `backend/internal/app/server.go`
- Modify: `backend/tests/integration_test.go`
- Modify: `app/src/main/java/com/airwar/android/net/MultiplayerApi.java`
- Modify: `app/src/main/java/com/airwar/android/net/MultiplayerSession.java`
- Modify: `app/src/main/java/com/airwar/android/net/NetworkConfig.java`
- Modify: `app/src/main/java/com/airwar/android/ui/LocalMultiplayerPrefs.java`
- Modify: `app/src/main/java/com/airwar/android/ui/MenuActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/RoomActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/GameActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/GameOverActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/LeaderboardActivity.java`
- Modify: `app/src/main/java/com/airwar/android/view/GameSurfaceView.java`
- Modify: `app/src/main/java/com/airwar/android/view/SpriteRepository.java`
- Modify: `app/src/main/java/com/airwar/android/audio/AndroidAudioManager.java`
- Modify: `game-core/src/main/java/com/airwar/core/engine/GameEngine.java`
- Modify: `app/src/main/res/layout/activity_menu.xml`
- Modify: `app/src/main/res/layout/activity_game.xml`
- Modify: `app/src/main/res/layout/activity_game_over.xml`
- Modify: `app/src/main/res/layout/activity_room.xml`
- Modify: `app/src/main/res/layout/view_hud_overlay.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: relevant Android tests under `app/src/androidTest/java/com/airwar/android/ui/`

### Task 1: Extend the shared protobuf contract for multiplayer room difficulty

**Files:**
- Modify: `proto/aircraft_war.proto`
- Modify: `backend/proto/aircraft_war.pb.go`
- Modify: `app/src/main/java/hitsz/aircraftwar/backend/AircraftWar.java`

- [ ] **Step 1: Add the failing contract change to the proto**

Update `proto/aircraft_war.proto` to add:

- a multiplayer difficulty field on `Room`
- a host-only room difficulty update request/response
- room difficulty propagation through existing room-state responses and broadcasts

- [ ] **Step 2: Regenerate Go and Android protobuf outputs**

Run:
`protoc --go_out=backend --java_out=lite:app/src/main/java proto/aircraft_war.proto`

Expected: Go and Android generated types now include room difficulty fields and update request/response types.

- [ ] **Step 3: Run narrow compile verification before implementation branches diverge**

Run:
`go test ./... -run TestDoesNotExist -count=1`

Workdir: `backend`

Expected: backend packages compile with the new contract.

### Task 2: Implement backend room difficulty authority and explicit final result timing

**Files:**
- Modify: `backend/internal/app/server.go`
- Modify: `backend/tests/integration_test.go`
- Modify: `backend/proto/aircraft_war.pb.go`

- [ ] **Step 1: Write failing backend tests first**

Extend `backend/tests/integration_test.go` to cover:

- room create initializes difficulty
- host can change room difficulty
- guest cannot change room difficulty
- updated room difficulty is pushed through `RoomStateBroadcast`
- both clients see the same room difficulty before start
- first-finished client receives terminal final result promptly once match result is known

- [ ] **Step 2: Run targeted backend tests to verify RED**

Run:
`go test ./tests -run 'TestRoomDifficultyInitializationAndBroadcast|TestGuestCannotChangeRoomDifficulty|TestFinalResultBroadcastUnblocksFinishedClient' -count=1`

Workdir: `backend`

Expected: FAIL because difficulty is not yet room-authoritative and final result timing is not yet tightened for the tested expectation.

- [ ] **Step 3: Implement minimal backend support**

Update `backend/internal/app/server.go` so that it:

- stores room difficulty in `roomState.room`
- initializes multiplayer room difficulty on create
- exposes a host-only difficulty update endpoint
- includes difficulty in room snapshots, state responses, and pushes
- ensures start-game uses stored room difficulty
- keeps final result delivery explicit and prompt for already-finished clients when the room result becomes known

- [ ] **Step 4: Re-run targeted backend tests to verify GREEN**

Run:
`go test ./tests -run 'TestRoomDifficultyInitializationAndBroadcast|TestGuestCannotChangeRoomDifficulty|TestFinalResultBroadcastUnblocksFinishedClient' -count=1`

Workdir: `backend`

Expected: PASS.

- [ ] **Step 5: Run full backend verification**

Run:
`go test ./... -count=1`

Workdir: `backend`

Expected: PASS.

### Task 3: Unify single-player scoring and make gameplay visuals difficulty-aware

**Files:**
- Modify: `game-core/src/main/java/com/airwar/core/engine/GameEngine.java`
- Modify: `app/src/main/java/com/airwar/android/view/GameSurfaceView.java`
- Modify: `app/src/main/java/com/airwar/android/view/SpriteRepository.java`
- Test: relevant `game-core/src/test/java/...`

- [ ] **Step 1: Add failing tests for local score rules and difficulty visuals where feasible**

Add or tighten tests so they express:

- local enemy scoring follows MOB=10, ELITE=20, BOSS=50
- bomb-related enemy clear scoring remains consistent with the unified local rule set
- gameplay background selection is driven by effective difficulty instead of a hardcoded default

- [ ] **Step 2: Run targeted verification to verify RED**

Run:
`./gradlew :game-core:test app:compileDebugJavaWithJavac`

Expected: FAIL on at least the new local score-rule expectation until implementation is updated.

- [ ] **Step 3: Implement minimal gameplay engine and render-path changes**

Update the local engine and render path so that:

- local score accumulation matches multiplayer server rules
- `GameSurfaceView` can resolve effective difficulty for background selection
- `SpriteRepository` returns the correct background asset for easy/normal/hard

- [ ] **Step 4: Re-run the verification to verify GREEN**

Run:
`./gradlew :game-core:test app:compileDebugJavaWithJavac`

Expected: PASS.

### Task 4: Restore single-player replay loop and add shared audio toggles on Android

**Files:**
- Modify: `app/src/main/java/com/airwar/android/ui/LocalMultiplayerPrefs.java`
- Modify: `app/src/main/java/com/airwar/android/ui/MenuActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/GameActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/GameOverActivity.java`
- Modify: `app/src/main/java/com/airwar/android/audio/AndroidAudioManager.java`
- Modify: `app/src/main/res/layout/activity_menu.xml`
- Modify: `app/src/main/res/layout/activity_game.xml`
- Modify: `app/src/main/res/layout/activity_game_over.xml`
- Modify: `app/src/main/res/layout/view_hud_overlay.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/com/airwar/android/ui/MenuActivityLaunchTest.java`
- Test: `app/src/androidTest/java/com/airwar/android/ui/GameActivityLaunchTest.java`

- [ ] **Step 1: Add failing Android UI expectations first**

Update or add tests that cover:

- menu shows a usable single-player start path
- game over in single-player exposes replay/menu actions
- audio toggle state persists between menu and game
- multiplayer and single-player HUDs differ appropriately

- [ ] **Step 2: Run narrow Android compile/test verification to verify RED**

Run:
`./gradlew app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: FAIL because the new button/state expectations are not implemented yet.

- [ ] **Step 3: Implement minimal Android flow changes**

Update Android code so that:

- `MenuActivity` can launch single-player directly again
- `GameOverActivity` renders mode-appropriate actions
- `GameActivity` resolves single-player vs multiplayer explicitly
- audio toggle state is shared and persisted between menu and gameplay
- `AndroidAudioManager` applies current enabled state consistently

- [ ] **Step 4: Re-run Android compile/test verification to verify GREEN**

Run:
`./gradlew app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: PASS.

### Task 5: Implement Android room difficulty UI and final multiplayer result handling

**Files:**
- Modify: `app/src/main/java/com/airwar/android/net/MultiplayerApi.java`
- Modify: `app/src/main/java/com/airwar/android/net/MultiplayerSession.java`
- Modify: `app/src/main/java/com/airwar/android/ui/RoomActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/GameActivity.java`
- Modify: `app/src/main/java/com/airwar/android/ui/GameOverActivity.java`
- Modify: `app/src/main/res/layout/activity_room.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/com/airwar/android/ui/RoomActivityLaunchTest.java`
- Test: `app/src/androidTest/java/com/airwar/android/ui/MenuToGameFlowTest.java`

- [ ] **Step 1: Add failing Android room/result expectations first**

Update or add tests that cover:

- room shows shared difficulty
- host can change difficulty and guest sees it as read-only state
- gameplay uses room difficulty in multiplayer
- multiplayer end screen transitions from temporary waiting state to explicit result

- [ ] **Step 2: Run narrow Android compile/test verification to verify RED**

Run:
`./gradlew app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: FAIL because room difficulty controls and final-result UI transitions are not yet implemented.

- [ ] **Step 3: Implement minimal Android room/result updates**

Update Android code so that:

- `MultiplayerApi` can call the room-difficulty update endpoint
- `MultiplayerSession` stores and surfaces difficulty-bearing room snapshots and terminal results cleanly
- `RoomActivity` renders room difficulty and host-only difficulty controls
- `GameActivity` uses room difficulty in multiplayer mode
- `GameOverActivity` leaves waiting state once final result is received explicitly

- [ ] **Step 4: Re-run Android compile/test verification to verify GREEN**

Run:
`./gradlew app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: PASS.

### Task 6: Run final integrated verification in the root branch

**Files:**
- Verify: `backend/...`
- Verify: `app/...`
- Verify: `game-core/...`
- Verify: `proto/aircraft_war.proto`

- [ ] **Step 1: Run backend verification**

Run:
`go test ./... -count=1`

Workdir: `backend`

Expected: PASS.

- [ ] **Step 2: Run Android and game-core verification**

Run:
`./gradlew :game-core:test app:compileDebugJavaWithJavac app:compileDebugAndroidTestJavaWithJavac`

Expected: PASS.

- [ ] **Step 3: Review final diff scope**

Run:
`git diff -- proto/aircraft_war.proto backend app game-core docs/superpowers/specs/2026-05-01-aircraft-war-difficulty-audio-score-design.md docs/superpowers/plans/2026-05-01-aircraft-war-difficulty-audio-score-implementation.md`

Expected: only the approved protocol, backend room difficulty/finalization, Android UI/state, local gameplay score logic, and supporting docs are changed.
