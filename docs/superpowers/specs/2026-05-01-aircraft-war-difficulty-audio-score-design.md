## Context

The current multiplayer work fixed room-state push, avatar identity, and leaderboard avatar persistence, but three gameplay-facing issues remain on top of that implementation:

- `GameActivity` still has leftover single-player score HUD behavior, so multiplayer score rendering is partly driven by local `GameStateSnapshot.score()` before it is corrected by server-backed session state.
- The audio layer already supports enabling/disabling playback, but there is no actual user-facing mute control or persisted audio preference.
- Difficulty selection currently affects the menu preview, but the in-game background does not follow the selected difficulty.

During clarification, the scope expanded in one important way: multiplayer difficulty must be shared at the room level so both players enter the same match difficulty and see the same background. That means this work is not client-only; room difficulty must become a server-backed room property.

The user also clarified that single-player mode should remain playable locally but must not participate in the global leaderboard, while single-player scoring rules should still be logically unified with the authoritative multiplayer scoring model.

## Goals

- Make multiplayer primary score HUD depend only on the server-backed session score, not local gameplay score.
- Keep single-player playable locally without using the global leaderboard flow.
- Restore a complete single-player replay loop through `MenuActivity` and `GameOverActivity`.
- Unify single-player and multiplayer enemy score values.
- Add a global sound toggle available in both `MenuActivity` and `GameActivity`.
- Persist the sound preference locally so menu and gameplay always agree.
- Make in-game background follow the effective difficulty.
- Make multiplayer room difficulty a shared server-backed room property controlled in-room and applied consistently to both players.
- Make multiplayer final result delivery explicit so finished clients do not remain stuck in a vague settling state.

## Non-Goals

- No redesign of core room matchmaking beyond adding shared room difficulty.
- No change to room size or general host/guest ownership rules.
- No device-specific audio routing features beyond a simple sound enabled/disabled preference.
- No migration of single-player into any remote persistence path.

## Recommended Approach

Use explicit mode-aware score rendering in `GameActivity`, add a shared room difficulty field to the multiplayer room contract, and introduce a small persisted client settings layer for sound.

This is preferred over ad-hoc UI patches because the current issues all come from ambiguous ownership of state:

- multiplayer score authority should belong to the server-backed session
- multiplayer difficulty should belong to the room
- sound preference should belong to a shared local settings source

Making those ownership boundaries explicit fixes the immediate bugs without over-architecting the client.

## Multiplayer Difficulty Design

### Ownership Model

- Single-player difficulty remains local and can still be chosen in `MenuActivity`.
- Multiplayer difficulty becomes part of room state and is therefore server-authoritative.

This means a local menu selection is no longer the final source of truth for multiplayer matches. The room snapshot is.

### Room Rules

Recommended room rule set:

- the host can change room difficulty from `RoomActivity`
- the guest can only view the current room difficulty
- changing room difficulty updates server room state immediately
- the backend pushes the updated room state to both players
- starting the game uses the room difficulty from room snapshot for both clients

This is the smallest rule set that guarantees both players use the same difficulty and background.

### Protocol And Backend Changes

Because the room difficulty must be guaranteed across both players, it cannot remain client-local.

Required shared contract changes:

- add a room difficulty field to `Room`
- add a request/response pair for host-updated room difficulty, or an equivalent room update mechanism scoped only to difficulty
- include room difficulty in all room-state responses and pushes

Backend behavior:

- room creation initializes room difficulty to the creator's chosen multiplayer difficulty or a default `normal`
- host difficulty change updates in-memory room state
- updated room state is broadcast to both clients
- start-game logic uses the already stored room difficulty without per-client divergence

## Score Logic Design

### Multiplayer Score HUD

`GameActivity` must clearly separate single-player and multiplayer display rules.

In multiplayer mode:

- the primary score HUD uses only `MultiplayerSession` self score
- local `GameStateSnapshot.score()` is not used as the visible score source
- self and opponent score lines remain visible and continue to use pushed session data

This removes the current local-then-server overwrite behavior and makes the screen consistently match the authoritative score.

### Single-Player Score HUD

In single-player mode:

- the primary score HUD continues to use local `GameStateSnapshot.score()`
- multiplayer self/opponent score rows are hidden or not rendered
- no room/session-dependent HUD state is shown

### Score Rule Unification

Single-player and multiplayer should use the same enemy score values:

- MOB = 10
- ELITE = 20
- BOSS = 50

This unifies game logic semantics across modes while preserving different persistence behavior.

### Leaderboard Boundary

- global leaderboard remains multiplayer-only
- single-player results do not enter the remote leaderboard flow

This preserves the clarified product rule while still keeping score values logically consistent between modes.

## Single-Player Flow Design

### Menu Entry

`MenuActivity` must expose a usable single-player start path again.

Expected behavior:

- player chooses local difficulty in `MenuActivity`
- player can immediately start a single-player match from `MenuActivity`
- single-player launch does not depend on any room/session state

This restores the missing replay loop entry point that currently prevents a clean single-player cycle.

### Game Over Loop

`GameOverActivity` must render different actions for single-player and multiplayer.

Single-player mode should offer:

- replay with the same local difficulty
- return to menu

It should not offer multiplayer-only actions such as remote leaderboard navigation semantics as the primary path.

The goal is that a player can finish a local game, see the score, and immediately choose whether to play again without entering any multiplayer-specific flow.

## Audio Design

### Shared Local Preference

Add a small persisted client setting for sound enabled state, for example `sound_enabled`.

Behavior:

- `MenuActivity` and `GameActivity` both read and write the same setting
- changing it in one place immediately defines the state used in the other
- entering gameplay respects the saved value without needing the player to re-toggle sound

### UI Placement

- `MenuActivity` gets a sound toggle as part of the local play/settings area
- `GameActivity` gets a small in-game mute control in the HUD

The two controls should reflect the same state rather than maintain independent session-only toggles.

### Audio Scope

The toggle controls all game audio output:

- gameplay BGM
- boss BGM
- bullet SFX
- hit SFX
- explosion SFX
- supply SFX
- game-over SFX

## Background And Difficulty Visuals

The current issue is not difficulty propagation into gameplay rules; it is background asset selection in the render path.

Required change:

- in-game background must depend on the effective difficulty used for the current match

Mapping:

- easy -> `bg2`
- normal -> `bg3`
- hard -> `bg5`

Implementation direction:

- `MenuActivity` continues to preview difficulty visually
- `GameSurfaceView` and `SpriteRepository` use the resolved gameplay difficulty to load the correct background asset

For multiplayer, the resolved gameplay difficulty must come from room state. For single-player, it comes from the local menu selection.

## Multiplayer Finalization Design

### Problem

The current end-of-match experience allows the first player who dies to remain in a vague waiting state because the client does not receive a sufficiently decisive final result transition at the UI level.

### Required Behavior

Multiplayer finalization should have two explicit phases:

- phase 1: current player is finished, but full match result is not yet determined
- phase 2: authoritative final result is determined and immediately communicated to both clients

Once phase 2 begins, the finished client must stop showing a generic settling message and instead show a concrete result:

- win
- lose
- draw
- disconnection-related finish outcome where relevant

### Backend Role

The backend already has the concept of final room result and terminal broadcasts. This work requires tightening that behavior so the client receives a stable and prompt terminal signal whenever the final result becomes known.

Required backend expectations:

- finishing one player may leave the room in a temporary waiting-for-opponent state
- once the room result is actually determined, the backend immediately broadcasts the final result to both players
- the broadcast timing must be good enough that the first finished player does not sit indefinitely in a non-terminal UI state

The preferred minimal implementation is to keep using the existing final-result broadcast path and make it the definitive client trigger for terminal UI.

### Client Role

`GameOverActivity` should treat final-result delivery as explicit state, not as something inferred only from polling.

Expected behavior:

- before final result: show a temporary waiting state
- after final result broadcast or final room state: immediately switch to concrete outcome presentation

This closes the multiplayer result loop so a player always learns the actual outcome promptly.

## Client Architecture Changes

### Settings Storage

Introduce or extend a local settings holder so that sound preference is not incorrectly stored in multiplayer-only preference semantics.

Reason:

- sound is a general client setting, not multiplayer identity
- difficulty preview and sound state should not be coupled to room identity persistence

This can still be a minimal extension of existing local prefs if done carefully, but the stored key ownership should remain clear.

### GameActivity Mode Resolution

`GameActivity` should explicitly resolve whether it is currently in single-player or multiplayer mode.

Expected behavior:

- multiplayer if valid room/session identity exists and gameplay was launched from room flow
- otherwise single-player

This mode resolution drives:

- score HUD source
- multiplayer score row visibility
- effective difficulty source
- game-over action model

### GameSurfaceView / SpriteRepository

The background resource selection should be data-driven by difficulty rather than fixed to a single default bitmap.

Keep changes minimal:

- avoid redesigning render thread structure
- only add enough difficulty-aware resource selection to align visuals with chosen difficulty

## Testing Strategy

### Backend

Add or update tests for:

- room difficulty initialization on create
- host-only room difficulty change
- room difficulty push after change
- start-game state preserves shared room difficulty for both clients

### Android

Add or update tests for:

- multiplayer `GameActivity` primary score HUD uses session-backed score source
- single-player `GameActivity` uses local score HUD and hides multiplayer score rows
- single-player menu flow can still launch gameplay and replay through game over
- sound toggle persistence across menu and gameplay
- room difficulty display/update rules in `RoomActivity`
- in-game background resource selection follows effective difficulty
- multiplayer `GameOverActivity` transitions from waiting state to explicit final result when terminal result arrives

## Scope Notes

This work touches both frontend and backend, but the server changes remain limited to the minimum required to make multiplayer difficulty shared and authoritative and to make final multiplayer result delivery explicit. Score display, sound control, and single-player replay behavior remain primarily client-side concerns.
