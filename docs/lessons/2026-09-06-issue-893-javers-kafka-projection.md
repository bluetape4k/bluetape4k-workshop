# Issue #893 JaVers Kafka snapshot projection

## Context

최초 `exposed/javers-persistence-audit` 모듈을 도입한 Issue #290은 Redis에 직접 쓰는
조회 가능한 audit history를 제공했다. dependencies 2.0.0이 JaVers Kafka snapshot
projector를 제공하므로, 기존 예제에 write-only Kafka stream과 Redis read model 사이의
소비자 경계를 추가해야 했다.

## Decision or Finding

- Command는 `VanillaKafkaCdoSnapshotRepository`로 발행하고 query/head는
  `LettuceCdoSnapshotRepository`에서 읽는 composite JaVers repository를 사용한다.
- 다음 command의 JaVers version과 snapshot type은 Redis head에 의존하므로 `place` 뒤
  replay 없이 `markPaid`나 `delete`를 수행하면 fail-closed로 거부한다.
- Consumer는 nonblank group id, `enable.auto.commit=false`, `auto.offset.reset=earliest`,
  single-partition topic만 허용한다.
- Application/operator가 single-partition topic을 미리 provision하고, 새 pipeline은 첫 mutation 전
  initial catch-up을 수행한다. 이후 모든 mutation은 다음 mutation 전 replay를 요구하므로 process
  restart 뒤 stale Redis head에서 새 snapshot을 계산하지 않는다.
- `replayUntilIdle` 기본값은 연속 empty poll 3회다. 최초 assignment poll이 비어도 다음
  record를 처리하지만, 장기 worker와 외부 deadline은 application이 소유한다.
- Batch의 pre-EXEC target failure에서는 offset을 commit하지 않는다. 실패 instance를 닫고
  같은 group의 새 consumer/projector를 시작하면 이미 반영된 snapshot은 skip하고 실패 지점부터
  replay한다.
- Redis `EXEC` 뒤 command error나 connection loss는 commit-unknown이며 partial projection이
  가능하다. 이 예제는 자동 복구나 exactly-once를 주장하지 않는다.

## Outcome

기존 bounded Redisson history 경로를 유지하면서 Kafka snapshot stream을 Lettuce Redis read
model로 복원하고 duplicate replay, restart rebuild, same-group retry를 학습할 수 있게 되었다.
`.github/workflows/Examples.yml`은 이미 module path와 smoke/full test task를 포함하므로 변경 없이
structural guard로 보존했다.

## Verification

- Testcontainers Kafka/Redis integration으로 initial/update projection과 restart rebuild 확인
- MockConsumer로 multi-partition poll-before rejection과 최초 empty poll 회귀 확인
- pre-EXEC 두 번째 snapshot 실패 뒤 새 same-group consumer의 skip/replay/offset commit 확인
- close가 모든 owned resource를 한 번씩 시도하고 후속 예외를 suppress하는지 확인
- 기존 Redis head와 미처리 Kafka backlog가 있는 restart에서 initial replay 전 mutation 거부 확인

## Future Guidance

Kafka projector consumer 예제는 multi-partition을 암묵적으로 허용하거나 Redis transaction을
rollback 가능한 것으로 표현하지 않는다. 운영 적용에는 TLS/SASL, ACL, authorization/redaction,
post-EXEC reconciliation, durable worker lifecycle을 별도 설계한다.
