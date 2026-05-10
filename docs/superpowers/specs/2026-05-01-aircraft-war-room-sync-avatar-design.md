## Context

The current multiplayer flow works for basic room creation, ready, start, in-game scoring, and final persistence, but several parts of the product are still split between HTTP polling, incomplete WebSocket push behavior, and local-only UI state.

The main gaps are:

- Room membership and ready-state changes are not proactively pushed by the server, so clients still depend on tapping a manual sync button to observe normal state changes.
- Avatar selection still lives in `GameOverActivity`, even though multiplayer users already establish identity before the game starts.
- The backend does not persist or return avatar identity, so the leaderboard always falls back to a default avatar.
- `GameActivity` does not show the opponent's live score during a multiplayer match.
- Some room UI logic is functionally correct enough to operate but still has misleading state presentation or overly broad enable/disable behavior.

This design updates the protocol and both clients so that room state becomes server-driven, avatar identity is established before matchmaking, and multiplayer score visibility is complete during play.

## Goals

- Make normal room-state updates fully server-pushed over WebSocket.
- Keep the room sync button only as a recovery tool, not a required interaction.
- Move avatar selection to `MenuActivity` and treat it as part of multiplayer identity.
- Persist avatar identity on the backend and return it in leaderboard responses.
- Show both self and opponent scores live during multiplayer matches.
- Remove end-of-game identity editing from `GameOverActivity`.
- Fix room UI issues that currently misrepresent host identity or button availability.

## Non-Goals

- No redesign of single-player flow.
- No change to the existing score authority model; the backend remains the source of truth for score accumulation.
- No introduction of a full reconnect/resume workflow beyond the existing sync fallback.
- No change to room size or matchmaking model; rooms remain two-player rooms.

## Recommended Approach

Use an explicit `RoomStateBroadcast` WebSocket message for room lifecycle state, keep `ScoreBroadcast` for in-match score changes, and extend multiplayer identity with `avatar_id` across the protocol and persistence model.

This is preferred over overloading `ScoreBroadcast` because room lifecycle and score updates are different concepts with different consumers. Splitting them now keeps the client state model clear and prevents further mixing of room membership events with score events.

## Protocol Changes

### Player Identity

Extend these messages with `avatar_id`:

- `Player`
- `CreateRoomRequest`
- `JoinRoomRequest`
- `LeaderboardEntry`

Behavioral rules:

- `username` and `avatar_id` together form the multiplayer identity used by the client for room entry and by the server for leaderboard persistence.
- The client always sends the locally selected avatar when creating or joining a room.
- The server stores the latest avatar seen for a given username when syncing leaderboard progress.

### Room Lifecycle Broadcast

Add a new message:

`RoomStateBroadcast`

Fields:

- `room`
- `scores`
- `room_finished`
- `result`
- `updated_at`

Add `room_state_broadcast` to `WsMessage.oneof`.

Behavioral rules:

- `RoomStateBroadcast` is the authoritative push message for room lifecycle and full room snapshot updates.
- `ScoreBroadcast` remains the authoritative push message for live score changes during play.
- `GameFinishedBroadcast` may remain as an explicit terminal event, but clients should treat `RoomStateBroadcast` as the canonical snapshot update path.

## Backend Design

### Room State Push Model

The backend sends `RoomStateBroadcast` whenever the full room snapshot materially changes:

- after successful room creation
- after a player joins
- after a player becomes ready
- after the host starts the game
- after a player finishes
- after the room becomes fully finished
- immediately after a WebSocket connection is established, as a one-client snapshot push

This makes the initial room page render and subsequent room-state changes work without relying on manual HTTP polling.

### Score Push Model

The backend continues to send `ScoreBroadcast` when authoritative player score changes happen during play, especially on `PlayerDefeatEvent`.

This gives `GameActivity` a lightweight real-time stream dedicated to score updates while keeping room lifecycle messages semantically separate.

### Persistence Changes

Extend leaderboard persistence with `avatar_id`.

Database changes:

- add `avatar_id` column to `leaderboard`

Persistence rules:

- whenever leaderboard progress is inserted or updated for a username, also upsert the current avatar id
- leaderboard queries return the stored avatar id through `LeaderboardEntry`

This ensures leaderboard UI can render the correct avatar chosen before match entry.

### Server Logic Adjustments

Expected backend updates include:

- create/join handlers capture avatar id from request and store it in player state
- room snapshots clone and expose player avatar ids
- `handleWebSocket` sends one immediate `RoomStateBroadcast` to the connecting player after registration
- `handleReadyRoom` and `handleJoinRoom` broadcast room snapshot updates to connected clients
- `handleStartGame` broadcasts both the updated room snapshot and the existing playing score state
- `finishPlayerLocked` broadcasts updated room state before or alongside terminal events

## Android Client Design

### MenuActivity

`MenuActivity` becomes the only place where multiplayer identity is edited before entering a room.

Changes:

- add avatar picker UI next to the existing multiplayer username/base URL inputs
- persist `avatar_id` in local multiplayer prefs
- include `avatar_id` when calling create room and join room

Behavior:

- the selected avatar is treated exactly like the username: part of the pre-match multiplayer identity
- users do not edit multiplayer avatar identity after the match ends

### MultiplayerSession

`MultiplayerSession` remains the single client state holder, but it must understand `RoomStateBroadcast` separately from `ScoreBroadcast`.

Expected behavior:

- receiving `RoomStateBroadcast` updates room, scores, roomFinished, and result in one pass
- receiving `ScoreBroadcast` updates scores only
- listeners continue to consume a single snapshot API

This keeps screen logic simple while making push updates complete enough for both room and in-game displays.

### RoomActivity

`RoomActivity` should behave like a live room status screen instead of a polling screen.

Changes:

- default rendering relies on `RoomStateBroadcast`
- keep the existing sync button as a recovery action only
- keep one initial sync request as a fallback for cold entry or recovery, but do not rely on it for normal updates
- fix owner display to show the actual host rather than always showing the current user
- tighten button enable logic so post-request state is recomputed from snapshot instead of using broad temporary toggles
- add clearer disabled styling for the start button, using light gray and clearer text when the room is not startable

Behavioral expectations:

- when the second player joins, the host sees it without manual sync
- when either player becomes ready, both clients see the state change without manual sync
- when the room enters playing state, clients transition based on pushed room state rather than a manual refresh

### GameActivity

`GameActivity` must expose live multiplayer score visibility for both players.

Changes:

- add a small in-game multiplayer score panel
- show self username/score and opponent username/score
- update the panel from `MultiplayerSession` score snapshots

Behavior:

- every score broadcast updates both sides' displayed scores
- if the opponent has no score entry yet, the UI still reserves the panel and shows a zero/default state instead of appearing broken

The design should remain minimal and consistent with the existing UI rather than becoming a heavy scoreboard overlay.

### GameOverActivity

`GameOverActivity` should become a pure result screen.

Changes:

- remove avatar selection UI and any local avatar state from the screen
- keep result/status rendering and leaderboard navigation
- continue to observe multiplayer session updates while waiting for the opponent to finish, if applicable

Behavior:

- users no longer choose or edit avatar after the game ends
- leaderboard identity is already complete before the match begins

### LeaderboardActivity

`LeaderboardActivity` should render server-returned avatar identity.

Changes:

- map each `LeaderboardEntry.avatar_id` to `PilotAvatarRegistry`
- stop always falling back to the default avatar except when the server value is missing or invalid

## Known Logic Corrections Included In Scope

The following issues should be corrected as part of this work because they directly affect the same flow:

1. `RoomActivity` currently shows the current username in the owner field, which is incorrect for guests.
2. `RoomActivity.setRoomActionEnabled()` currently overrides finer-grained snapshot-based availability rules and should be replaced with snapshot-driven recomputation after requests complete.
3. `RoomActivity` currently treats manual room sync as a primary data source; after this change it becomes fallback-only.
4. `GameOverActivity` currently exposes identity editing in the wrong lifecycle stage.
5. `LeaderboardActivity` currently cannot render correct avatars because the data model does not carry them.

## Error Handling

- If WebSocket room-state push is unavailable, the manual sync button remains available as a recovery path.
- If the client has no valid stored avatar id, it falls back to `PilotAvatarRegistry.DEFAULT_AVATAR_ID` before create/join requests.
- If the leaderboard returns an unknown avatar id, the client falls back to the default avatar instead of failing the row render.
- If a room-state push arrives without a result, the client keeps the existing result until a later terminal snapshot or `GameFinishedBroadcast` arrives.

## Testing Strategy

### Backend

Add or update tests for:

- host receives pushed room-state update after second player joins
- both players receive pushed room-state update after ready actions
- connecting WebSocket receives an immediate room snapshot
- `PlayerDefeatEvent` still produces `ScoreBroadcast` updates for both players
- leaderboard persistence stores and returns `avatar_id`

### Android

Add or update tests for:

- `RoomActivity` updates from pushed room-state data without tapping sync
- `GameActivity` renders self and opponent score views in multiplayer mode
- in-game score views update when session scores change
- `GameOverActivity` no longer shows avatar selection controls
- `LeaderboardActivity` uses returned avatar ids for row rendering
- room start button disabled state is visually obvious and logically correct

## Branching And Execution Plan

Implementation will be split across branches after the protocol update:

1. Update `proto/aircraft_war.proto` and generated code in the current `feature/network` branch.
2. Implement backend room-state push and avatar persistence in `network-backend`.
3. Implement Android identity, room, in-game score, game-over, and leaderboard updates in `network-frontend`.
4. Merge both branches back into `feature/network` after verification.

This ordering keeps generated protocol artifacts consistent before the frontend and backend diverge into parallel work.
