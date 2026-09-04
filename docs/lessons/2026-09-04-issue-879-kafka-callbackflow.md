# Issue #879 Kafka producer callbackFlow bridge

## Context

`messaging/kafka-reply`는 `ReplyingKafkaTemplate` request-reply만 보여주고 있어, Kafka producer callback을
coroutine `Flow` 소비자가 다룰 때 필요한 소유권·취소·backpressure 경계를 설명하지 못했다. 2.0.0 consumer 예제는
기존 request-reply 회귀를 유지하면서 새 adapter를 별도 표면으로 제공해야 했다.

## Decision or Finding

- `KafkaProducerFlow`는 `Flow<ProducerRecord<String, String>>`를 `Flow<RecordMetadata>`로 변환한다.
- cold flow를 collection할 때마다 producer를 하나 만들고, 그 collection이 만든 producer만 `flush`/`close`한다.
- `maxInFlight` semaphore와 bounded channel로 producer callback의 동시성과 downstream backpressure를 제한한다.
- callback failure는 첫 원인을 terminal cause로 유지하고, cleanup failure는 primary cause에 suppressed exception으로
  추가한다.
- callback이 metadata와 failure를 모두 전달하지 않는 malformed 결과, 이미 취소된 collection에 도착한 late callback,
  producer future 취소를 명시적으로 검증한다.
- 외부 Kafka 연동은 Testcontainers로 한 번 검증하되, 기본 사용법은 producer factory를 주입하는 library-neutral
  adapter로 남긴다.

## Outcome

기존 `PingController` request-reply 코드는 변경하지 않고, 별도 `KafkaProducerFlow` 예제와 fake/실 Kafka 테스트를
추가했다. README 양쪽 언어와 root coverage matrix, Examples workflow의 path/실행/artefact, stale-check guard가
같은 계약을 가리킨다. 모든 dependency alias는 root `bluetape4k-dependencies` BOM에서 `2.0.0`으로 해석되며
`2.1.0-SNAPSHOT`을 사용하지 않는다.

## Verification

- fake callbackFlow lifecycle tests: 9 passing
- Kafka Testcontainers round trip: 1 passing
- `./gradlew :messaging-kafka-reply:test --no-daemon`: 10 passing, 1 pre-existing pending test
- `./gradlew :messaging-kafka-reply:build -x test --no-daemon`: successful
- `scripts/smoke-validate.sh stale-check`: all registration guards passing
- README parity/language, `actionlint .github/workflows/Examples.yml`, JSON validation, and `git diff --check`: passing
- stacked PR scope checker: `PASS ecosystem-reuse inventory and train contract`; #878 parent paths are included in
  the #879 `stacked-parent-head` scope with fresh coordinator receipt `20260904T-issue-879-kafka-callbackflow-scope`

## Future Guidance

새 callback 기반 Kafka API를 추가할 때는 callback 하나를 곧바로 `emit`하는 adapter를 만들지 말고, collection-scoped
producer ownership, bounded in-flight, cancellation propagation, late callback no-op, flush/close timeout과
suppressed cleanup을 먼저 계약으로 고정한다. 실제 broker 테스트는 deterministic fake 테스트와 분리해 실행 시간과
실패 원인을 구분한다.
