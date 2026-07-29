# Issue 370 Kafka Outbox SQL Pushdown Review

날짜: 2026-07-03

범위:
- `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/publication/EventPublicationRepository.kt`
- `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/publication/PublicationReconciler.kt`
- `messaging/kafka-outbox-fallback/src/test/kotlin/io/bluetape4k/workshop/messaging/fallback/KafkaOutboxFallbackFlowTest.kt`
- `messaging/kafka-outbox-fallback/README.md`
- `messaging/kafka-outbox-fallback/README.ko.md`

## 7-Tier Findings

남은 P0/P1 발견사항: 없음.

PR 전에 해결됨:
- `claimNextBatch`는 모든 publication row를 선택한 뒤 Kotlin에서 eligibility와 batch size를 적용했다. 이제 retryable status, cutoff, claim expiry, deterministic ordering, limit을 SQL에서 먼저 적용한 뒤 conditional claim update를 수행한다.
- Reconciliation은 이전에 publication row가 없는 모든 order를 load한 뒤 Kotlin에서 `reconcilerGrace`를 적용했다. 이제 cutoff와 missing-publication detection을 correlated SQL `NOT EXISTS` query로 pushdown한다.
- Regression coverage는 이제 relay eligibility ordering, batch limit, cutoff handling, anti-join behavior를 확인한다.
- README operational note는 learner에게 relay/reconciler eligibility, ordering, limit, missing-row detection이 SQL에 머문다고 알려 준다.

## 검증 근거

- `mcp__code_review_graph.detect_changes_tool(base=origin/develop)`: risk score `0.00`, test gaps `0`.
- `node scripts/validate-readme-parity.mjs`: `failures=0`.
- `node scripts/validate-readme-language.mjs`: `offenders=0`.
- `git diff --check`: `PASS`.
- `rg 'selectAll\\(\\)\\s*$|\\.filter \\{|\\.take\\(' messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/publication -S`: scheduled path에는 Kotlin `filter/take` 없음. 남은 `.take(240)` occurrence는 string sanitization뿐이다.
- `./gradlew :messaging-kafka-outbox-fallback:compileTestKotlin --no-build-cache --warning-mode all --console=plain`: `BUILD SUCCESSFUL`; root Gradle 10 deprecation warning은 기존 항목이며 무관하다.
- `./gradlew :messaging-kafka-outbox-fallback:test --tests 'io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackFlowTest' --no-build-cache --warning-mode all --console=plain --max-workers=1`: `BUILD SUCCESSFUL`, `18 tests`.

## 검토 메모

핵심 판단은 scheduled path에서 대량 row를 Kotlin으로 끌어온 뒤 filtering하지 않고, DB가 eligibility와 cutoff를 먼저 좁히도록 바뀌었는지다. README도 같은 운영 경계를 설명하므로 learner-facing 문서와 repository 구현 방향이 일치한다.

## 잔여 위험

correlated `NOT EXISTS` expression은 module의 H2-backed integration test가 다룬다. Production PostgreSQL indexing strategy는 이 workshop issue 범위 밖이며, 예제가 production guidance가 된다면 별도로 다뤄야 한다.
