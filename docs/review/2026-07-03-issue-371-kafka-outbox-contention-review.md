# Issue 371 Kafka Outbox Contention Test Review

Date: 2026-07-03

Scope:
- `messaging/kafka-outbox-fallback/src/test/kotlin/io/bluetape4k/workshop/messaging/fallback/KafkaOutboxFallbackFlowTest.kt`

## 7-Tier Findings

P0/P1 findings: none remaining.

Resolved before PR:
- The previous test named `concurrent relay calls cannot claim the same row twice` called `claimNextBatch` sequentially, so it did not exercise the conditional claim update under contention.
- The test now uses bluetape4k-junit5 `MultithreadingTester` with two workers and one round.
- A `CyclicBarrier` aligns both worker tasks before `claimNextBatch(..., 1)` so the test covers simultaneous claim attempts without sleeps or ad hoc stress loops.
- The assertions prove exactly one worker receives the claim, exactly one worker observes an empty claim result, and the stored `claimedBy` matches the winning worker.

## Helper Choice

`MultithreadingTester` is the primary concurrency helper. Its implementation uses a fixed thread pool and waits for all futures, but it does not provide a start barrier. The test adds a small `CyclicBarrier(2)` only to align the two `MultithreadingTester` workers at the critical claim call.

## Verification Evidence

- `mcp__code_review_graph.detect_changes_tool(base=origin/develop)`: risk score `0.00`, test gaps `0`.
- `./gradlew :messaging-kafka-outbox-fallback:test --tests 'io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackFlowTest.concurrent relay calls cannot claim the same row twice' --no-build-cache --warning-mode all --console=plain --max-workers=1`: `BUILD SUCCESSFUL`, `1 test`.
- `./gradlew :messaging-kafka-outbox-fallback:test --tests 'io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackFlowTest' --no-build-cache --warning-mode all --console=plain --max-workers=1`: `BUILD SUCCESSFUL`, `18 tests`.
- `git diff --check`: `PASS`.

## Residual Risk

The test verifies the H2-backed workshop claim path. Database-engine-specific lock behavior beyond the guarded update contract remains outside this issue.
