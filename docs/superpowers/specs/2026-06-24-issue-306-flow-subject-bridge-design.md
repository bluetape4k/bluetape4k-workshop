# Issue 306 Flow Subject Bridge Design

## Problem

Issue #306 asks for a callback-to-Flow bridge workshop example that teaches when to use bluetape4k Subject types instead of raw `callbackFlow`, `MutableSharedFlow`, manual channel closing, and implicit replay buffers.

## Source evidence

- `SubjectApi<T>` exposes `emit`, `emitError`, `complete`, `hasCollectors`, and `collectorCount`.
- `PublishSubject` broadcasts only values emitted after a collector is active.
- `BehaviorSubject` keeps the latest state and sends it to late collectors.
- `ReplaySubject` replays buffered history to late collectors.
- `MulticastSubject(expectedCollectorSize)` waits until the expected collector count is registered before producer progress.
- `UnicastWorkSubject` stores work in a single-consumer queue and rejects simultaneous collectors.
- Existing workshop modules under `kotlin/` use small in-memory examples with module-local README pairs and JUnit 5 tests.

## Design

Create `kotlin/flow-extensions-subject-bridge` as an in-memory coroutine example. The main type is `DeviceSubjectBridge`, which exposes read-only `Flow` views and callback-style write methods:

- `events`: event-only stream backed by `PublishSubject`.
- `latestState`: latest-state stream backed by `BehaviorSubject`.
- `history`: replayable event history backed by bounded `ReplaySubject`.
- `multicastEvents`: coordinated fan-out backed by `MulticastSubject`.
- `workItems`: single-consumer work queue backed by `UnicastWorkSubject`.

The bridge keeps Subject mutation behind methods such as `publishEvent`, `updateState`, `multicastEvent`, `enqueueWork`, `complete*`, and `fail*`. This keeps the example focused on bridge semantics rather than encouraging arbitrary Subject mutation throughout application code.

## Rejected approaches

1. Raw `callbackFlow` as the main implementation: useful as a baseline, but it would hide Subject selection semantics behind channel plumbing.
2. Expose Subject instances directly as public properties: shorter code, but easier for README readers to copy into application-wide mutable hot streams.
3. Use real WebSocket/file watcher SDK callbacks: realistic, but it would add irrelevant infrastructure and make the Subject contract harder to see.

## Risks and mitigations

- Hot-stream misuse: README explicitly states that Subjects are bridge tools, not default architecture.
- Hanging multicast emit: tests call `awaitMulticastSubscribers` before `multicastEvent`, and README documents this waiting behavior.
- Ambiguous null terminal error: tests cover `emitError(null)` on the work queue and README explains that it is not a termination signal for `UnicastWorkSubject`; use `complete()` for normal completion.
- Concurrent collector confusion: `UnicastWorkSubject` tests demonstrate single-consumer behavior and simultaneous collector rejection.

## Acceptance criteria mapping

- Subject selection guide: module README EN/KO.
- Publish, behavior, replay, multicast, unicast examples: `DeviceSubjectBridge` plus tests.
- Completion/error/null-error coverage: tests.
- Before/After callbackFlow versus Subject explanation: README EN/KO.
- Used Bluetape4k features table: README EN/KO.
- Diagrams: scenario, architecture, ERD/domain, class, sequence assets under `docs/images/readme-diagrams`.
