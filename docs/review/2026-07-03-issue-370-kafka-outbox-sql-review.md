# Issue 370 Kafka Outbox SQL Pushdown Review

Date: 2026-07-03

Scope:
- `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/publication/EventPublicationRepository.kt`
- `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/publication/PublicationReconciler.kt`
- `messaging/kafka-outbox-fallback/src/test/kotlin/io/bluetape4k/workshop/messaging/fallback/KafkaOutboxFallbackFlowTest.kt`
- `messaging/kafka-outbox-fallback/README.md`
- `messaging/kafka-outbox-fallback/README.ko.md`

## 7-Tier Findings

P0/P1 findings: none remaining.

Resolved before PR:
- `claimNextBatch` selected all publication rows, then applied eligibility and batch size in Kotlin. It now applies retryable status, cutoff, claim expiry, deterministic ordering, and limit in SQL before conditional claim updates.
- Reconciliation previously loaded all orders without publication rows and then applied `reconcilerGrace` in Kotlin. It now pushes cutoff and missing-publication detection into a correlated SQL `NOT EXISTS` query.
- Regression coverage now checks relay eligibility ordering, batch limits, cutoff handling, and anti-join behavior.
- README operational notes now tell learners that relay/reconciler eligibility, ordering, limits, and missing-row detection stay in SQL.

## Verification Evidence

- `mcp__code_review_graph.detect_changes_tool(base=origin/develop)`: risk score `0.00`, test gaps `0`.
- `node scripts/validate-readme-parity.mjs`: `failures=0`.
- `node scripts/validate-readme-language.mjs`: `offenders=0`.
- `git diff --check`: `PASS`.
- `rg 'selectAll\\(\\)\\s*$|\\.filter \\{|\\.take\\(' messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/publication -S`: no scheduled-path Kotlin `filter/take`; remaining `.take(240)` occurrences are string sanitization only.
- `./gradlew :messaging-kafka-outbox-fallback:compileTestKotlin --no-build-cache --warning-mode all --console=plain`: `BUILD SUCCESSFUL`; root Gradle 10 deprecation warnings are pre-existing and unrelated.
- `./gradlew :messaging-kafka-outbox-fallback:test --tests 'io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackFlowTest' --no-build-cache --warning-mode all --console=plain --max-workers=1`: `BUILD SUCCESSFUL`, `18 tests`.

## Residual Risk

The correlated `NOT EXISTS` expression is covered by the module's H2-backed integration test. Production PostgreSQL indexing strategy is outside this workshop issue and should be handled separately if the example becomes production guidance.
