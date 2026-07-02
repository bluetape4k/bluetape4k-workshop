# Issue 369 Kafka Outbox Disabled Publish Docs Review

Date: 2026-07-03

Scope:
- `messaging/kafka-outbox-fallback/README.md`
- `messaging/kafka-outbox-fallback/README.ko.md`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`
- `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/domain/PlaceOrderUseCase.kt`

## 7-Tier Findings

P0/P1 findings: none remaining.

Resolved before PR:
- The README flow described direct publish failure and timeout, but the disabled direct-publish path was not modeled as its own branch. The flow now states that `direct-publish-enabled=false` skips Kafka, writes a `DIRECT_DISABLED` `NOT_PUBLISHED` row, and returns `FALLBACK_STORED`.
- The sequence diagram only showed the failure/timeout fallback path. It now has a transparent `alt` region with a disabled direct-publish branch and an `else` branch for Kafka failure or timeout.
- The `PlaceOrderUseCase` KDoc still described direct publish and fallback persistence as future work. It now documents the current `OrderEventPublisher` contract.
- English and Korean README updates remain source-equivalent, including the status table and configuration notes.

## Verification Evidence

- `node scripts/validate-readme-parity.mjs`: `failures=0`.
- `node scripts/validate-readme-language.mjs`: `offenders=0`.
- `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`: `PASS`, `weak_reference_rows=0`, marker audit `PASS`, connector geometry `PASS`, fallback sequence audit `labels=9 numbers=9 monotonic=true alt_fill_failures=0`, sequence validator `PASS`, sequence style audit `PASS`.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-sequence-style-audit.py docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`: `PASS`, `sequence_files=1`.
- `xmllint --noout docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`: `PASS`.
- `./gradlew :messaging-kafka-outbox-fallback:compileKotlin --no-build-cache --warning-mode all --console=plain`: `BUILD SUCCESSFUL`; root build deprecation warnings are pre-existing and unrelated to this KDoc-only Kotlin edit.
- `git diff --check`: `PASS`.
- Rendered PNG eye check: disabled direct publish has no Kafka call, the failure branch shows Kafka send then fallback upsert, the `alt` region is transparent, labels are readable, and path/arrow marker colors match.

## Residual Risk

This issue only aligns docs, KDoc, and the sequence diagram with existing behavior. SQL pushdown and contention coverage remain tracked by #370 and #371.
