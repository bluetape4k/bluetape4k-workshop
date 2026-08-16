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

## Bounded-wait HTTP idempotency

![Bounded-wait idempotency sequence](../../docs/images/readme-diagrams/operations-job-console-bounded-wait-idempotency-01.png)

`POST /v1/jobs` uses a PostgreSQL-owned request row. The default rollout is
disabled (`job-console.bounded-wait.enabled=false`); enable it only when every
instance uses the same policy fingerprint. Defaults are a 2s waiter deadline,
two waiters per key, a 1h terminal retention window, 64KiB request/replay
bodies, a 255-byte idempotency key, and `Retry-After: 1` for timeout or `2` for
waiter overflow.

The first accepted owner commits the job, outbox, history, and HTTP snapshot in
one transaction. A replay returns that stored snapshot and never recomputes the
response. A legacy (disabled rollout) replay still uses the V001-compatible
terminal path. This is at-least-once processing with an idempotent HTTP
contract, not exactly-once execution.

| Outcome | Status | Caller action |
| --- | ---: | --- |
| first owner or replay | `202` | persist the response and stop retrying |
| same key, different request | `409` | fix the idempotency key/payload |
| in-flight deadline | `409` + `Retry-After: 1` | retry the same key |
| waiter cap | `429` + `Retry-After: 2` | back off and retry the same key |
| owner abandoned / dependency unavailable | `503` | retry the same key with backoff |
| invalid request / body too large | `400` / `413` | correct the request; do not retry blindly |

Shutdown closes admission first, drains active submissions for at most five
seconds, then abandons any remaining owner so lease recovery can take over.
Readiness reports PostgreSQL, Redis, policy fingerprint, bounded-wait state,
and a `postgres` or `policy` reason. Redis is advisory and does not make the
service unready. The test-only conformance host is not a production route.

## Test fixtures

The `testFixtures` source set provides the PostgreSQL/Redis container fixture,
barriers, a deterministic clock, HTTP driver, event probe, and the shared
black-box contract used by both adapters.

## Verify

```bash
./gradlew :operations-job-console-core:test
./gradlew :operations-job-console-core:integrationTest --max-workers=1
```

## High-contention evidence

![High-contention profile runner architecture](../../docs/images/readme-diagrams/high-contention-profile-runner-architecture-01.png)

Run the repository-wide profiles with a running Docker daemon, JDK 25, and at
least 4 GiB of memory available to Gradle and the containers:

```bash
CI_RUN_ID=developer-ci-001
REFERENCE_RUN_ID=developer-reference-001
./gradlew highContentionCi -PhighContentionRunId="$CI_RUN_ID" --max-workers=1
./gradlew highContentionLocalReference -PhighContentionRunId="$REFERENCE_RUN_ID" --max-workers=1
```

The correctness gate is `highContentionCi`. `highContentionLocalReference`
records environment-specific execution observations; it does not rank
frameworks and does not establish production capacity. Canonical reports are
written under `build/reports/high-contention/<run-id>/`. Use a new run ID for
every command; local-reference execution also requires a clean worktree.

PostgreSQL remains the authority for leases, fencing, checkpoints, deduplication,
and terminal state. Toxiproxy is limited to cutting and restoring the Redis path,
including old and newly opened connections; it does not simulate PostgreSQL
authority or database failover.
