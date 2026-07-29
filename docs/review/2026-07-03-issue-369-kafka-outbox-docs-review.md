# Issue 369 Kafka Outbox Disabled Publish Docs Review

날짜: 2026-07-03

범위:
- `messaging/kafka-outbox-fallback/README.md`
- `messaging/kafka-outbox-fallback/README.ko.md`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`
- `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/domain/PlaceOrderUseCase.kt`

## 7-Tier Findings

남은 P0/P1 발견사항: 없음.

PR 전에 해결됨:
- README flow는 direct publish failure와 timeout을 설명했지만 disabled direct-publish path를 자체 branch로 모델링하지 않았다. 이제 flow는 `direct-publish-enabled=false`가 Kafka를 건너뛰고, `DIRECT_DISABLED` `NOT_PUBLISHED` row를 쓰며, `FALLBACK_STORED`를 반환한다고 설명한다.
- Sequence diagram은 failure/timeout fallback path만 보여 주었다. 이제 transparent `alt` region 안에 disabled direct-publish branch와 Kafka failure 또는 timeout용 `else` branch가 있다.
- `PlaceOrderUseCase` KDoc은 여전히 direct publish와 fallback persistence를 future work로 설명했다. 이제 현재 `OrderEventPublisher` contract를 문서화한다.
- English/Korean README update는 status table과 configuration note를 포함해 source-equivalent 상태를 유지한다.

## 검증 근거

- `node scripts/validate-readme-parity.mjs`: `failures=0`.
- `node scripts/validate-readme-language.mjs`: `offenders=0`.
- `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`: `PASS`, `weak_reference_rows=0`, marker audit `PASS`, connector geometry `PASS`, fallback sequence audit `labels=9 numbers=9 monotonic=true alt_fill_failures=0`, sequence validator `PASS`, sequence style audit `PASS`.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-sequence-style-audit.py docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`: `PASS`, `sequence_files=1`.
- `xmllint --noout docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`: `PASS`.
- `./gradlew :messaging-kafka-outbox-fallback:compileKotlin --no-build-cache --warning-mode all --console=plain`: `BUILD SUCCESSFUL`; root build deprecation warning은 기존 항목이며 이 KDoc-only Kotlin edit와 무관하다.
- `git diff --check`: `PASS`.
- Rendered PNG eye check: disabled direct publish에는 Kafka call이 없고, failure branch는 Kafka send 후 fallback upsert를 보여 준다. `alt` region은 transparent이고, label은 readable하며 path/arrow marker color가 일치한다.

## 잔여 위험

이 issue는 docs, KDoc, sequence diagram을 기존 동작과 맞추는 데 한정된다. SQL pushdown과 contention coverage는 #370 및 #371에서 계속 추적한다.
