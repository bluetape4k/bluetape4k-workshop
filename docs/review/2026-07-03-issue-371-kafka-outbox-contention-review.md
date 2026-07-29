# Issue 371 Kafka Outbox Contention Test Review

날짜: 2026-07-03

범위:
- `messaging/kafka-outbox-fallback/src/test/kotlin/io/bluetape4k/workshop/messaging/fallback/KafkaOutboxFallbackFlowTest.kt`

## 7-Tier Findings

남은 P0/P1 발견사항: 없음.

PR 전에 해결됨:
- 이전 `concurrent relay calls cannot claim the same row twice` 테스트는 `claimNextBatch`를 순차 호출했기 때문에 contention 상황의 conditional claim update를 실행하지 않았다.
- 테스트는 이제 bluetape4k-junit5 `MultithreadingTester`를 두 worker와 한 round로 사용한다.
- `CyclicBarrier`는 `claimNextBatch(..., 1)` 전에 두 worker task를 정렬하므로, sleep이나 ad hoc stress loop 없이 simultaneous claim attempt를 다룬다.
- assertion은 정확히 한 worker만 claim을 받고, 정확히 한 worker가 empty claim result를 관찰하며, 저장된 `claimedBy`가 winning worker와 일치함을 증명한다.

## Helper Choice

`MultithreadingTester`가 primary concurrency helper다. 구현은 fixed thread pool을 사용하고 모든 future를 기다리지만 start barrier는 제공하지 않는다. 테스트는 critical claim call에서 두 `MultithreadingTester` worker를 맞추기 위해 작은 `CyclicBarrier(2)`만 추가한다.

## 검증 근거

- `mcp__code_review_graph.detect_changes_tool(base=origin/develop)`: risk score `0.00`, test gaps `0`.
- `./gradlew :messaging-kafka-outbox-fallback:test --tests 'io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackFlowTest.concurrent relay calls cannot claim the same row twice' --no-build-cache --warning-mode all --console=plain --max-workers=1`: `BUILD SUCCESSFUL`, `1 test`.
- `./gradlew :messaging-kafka-outbox-fallback:test --tests 'io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackFlowTest' --no-build-cache --warning-mode all --console=plain --max-workers=1`: `BUILD SUCCESSFUL`, `18 tests`.
- `git diff --check`: `PASS`.

## 잔여 위험

테스트는 H2-backed workshop claim path를 검증한다. guarded update contract를 넘어서는 database-engine-specific lock behavior는 이 issue 범위 밖이다.
