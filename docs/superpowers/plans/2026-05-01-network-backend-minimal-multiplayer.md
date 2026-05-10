# Network Backend Minimal Multiplayer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend minimal two-player multiplayer loop defined by `proto/aircraft_war.proto` and the 2026-05-01 protocol/spec docs, including HTTP room flow, `WsMessage` WebSocket transport, authoritative scoring, disconnect handling, state recovery, final results, and leaderboard persistence.

**Architecture:** Keep a single in-memory room manager in `backend/internal/app/server.go` backed by SQLite for room results and leaderboard. Use protobuf binary for every HTTP and WebSocket payload, with WebSocket carrying only `pb.WsMessage` frames that wrap the existing event/broadcast messages. Keep the current integration-test-first approach in `backend/tests/integration_test.go` and evolve the implementation only to satisfy those tests.

**Tech Stack:** Go, Gorilla WebSocket, `google.golang.org/protobuf`, SQLite via `modernc.org/sqlite`, Go `net/http`, `httptest`.

---

## File Structure

- Create: `docs/superpowers/plans/2026-05-01-network-backend-minimal-multiplayer.md`
- Modify: `backend/tests/integration_test.go`
- Modify: `backend/internal/app/server.go`
- Modify: `backend/internal/protoframe/protoframe.go`
- Modify: `backend/proto/aircraft_war.pb.go`
- Verify: `backend/cmd/server/main.go`

### Task 1: Switch tests and runtime to `WsMessage`

**Files:**
- Modify: `backend/tests/integration_test.go`
- Modify: `backend/internal/app/server.go`
- Modify: `backend/internal/protoframe/protoframe.go`
- Modify: `backend/proto/aircraft_war.pb.go`

- [ ] **Step 1: Write the failing test change**

Update `backend/tests/integration_test.go` so all WebSocket writes send `pb.WsMessage` wrappers and all reads first decode `pb.WsMessage`, then extract `ScoreBroadcast` or `GameFinishedBroadcast`.

- [ ] **Step 2: Run tests to verify they fail for the expected reason**

Run: `go test ./tests -run 'TestRoomLifecycleScoreResultAndLeaderboard|TestDisconnectFreezesScoreAndStateRecovery' -count=1`
Expected: FAIL because the current server still reads and writes bare protobuf WS payloads.

- [ ] **Step 3: Implement minimal runtime changes**

Regenerate `backend/proto/aircraft_war.pb.go` from the updated proto, replace old wire-sniffing logic with `pb.WsMessage` marshal/unmarshal in `server.go`, and keep helper logic minimal.

- [ ] **Step 4: Run tests to verify they pass**

Run: `go test ./tests -run 'TestRoomLifecycleScoreResultAndLeaderboard|TestDisconnectFreezesScoreAndStateRecovery' -count=1`
Expected: PASS.

### Task 2: Verify the HTTP room flow, scoring, disconnect, state recovery, and persistence behavior still match the protocol

**Files:**
- Modify: `backend/tests/integration_test.go`
- Modify: `backend/internal/app/server.go`

- [ ] **Step 1: Extend or tighten failing assertions only where protocol behavior is currently underspecified**

Add assertions for `room_finished`, finish reasons, and recovery-state/result fields only if the current tests do not already cover them.

- [ ] **Step 2: Run the targeted tests to verify any new assertions fail first**

Run: `go test ./tests -run 'TestRoomLifecycleScoreResultAndLeaderboard|TestDisconnectFreezesScoreAndStateRecovery' -count=1`
Expected: FAIL only if a newly asserted protocol requirement is not implemented yet.

- [ ] **Step 3: Implement the smallest code changes necessary**

Only adjust `backend/internal/app/server.go` if the stricter assertions expose a real mismatch with the spec.

- [ ] **Step 4: Re-run the targeted tests**

Run: `go test ./tests -run 'TestRoomLifecycleScoreResultAndLeaderboard|TestDisconnectFreezesScoreAndStateRecovery' -count=1`
Expected: PASS.

### Task 3: Run full backend verification

**Files:**
- Verify: `backend/...`

- [ ] **Step 1: Run the full backend test suite**

Run: `go test ./... -count=1`
Expected: PASS.

- [ ] **Step 2: Verify the server binary builds**

Run: `go test ./cmd/server -count=1`
Expected: PASS.

- [ ] **Step 3: Review git diff for scope control**

Run: `git diff -- backend proto/aircraft_war.proto docs/superpowers/plans/2026-05-01-network-backend-minimal-multiplayer.md`
Expected: Only backend implementation and plan changes relevant to the protocol baseline.
