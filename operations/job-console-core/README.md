# Job Console Core

[한국어](README.ko.md) | English

The Java 25 core owns the framework-neutral job contract, PostgreSQL migration,
FIFO queue, leases, checkpoints, terminal transitions, bounded ETA projection,
outbox polling, cancellation signaling, and cross-adapter test fixtures.

## Authority boundary

PostgreSQL is authoritative. Redis cancellation publish and SSE fan-out are
best effort. A failed advisory publish never rolls back the committed
`cancel_requested` state, and a slow subscriber never blocks outbox progress.

## State machine

![Job console state machine](../../docs/images/readme-diagrams/operations-job-console-readme-state-01.png)

The closed transition table rejects stale revisions and every signal from a
terminal state. Retryable failure returns to `queued` without changing the job
or queue identity.

## Test fixtures

The `testFixtures` source set provides the PostgreSQL/Redis container fixture,
barriers, a deterministic clock, HTTP driver, event probe, and the shared
black-box contract used by both adapters.

## Verify

```bash
./gradlew :operations-job-console-core:test
./gradlew :operations-job-console-core:integrationTest --max-workers=1
```
