# Issue #893 JaVers Kafka snapshot projection 설계

## 1. 목표

`exposed/javers-persistence-audit`에 JaVers Kafka write repository에서
`KafkaCdoSnapshotProjector`를 거쳐 transactional Lettuce Redis repository로 이어지는 실행 가능한
projection 예제를 추가한다. 재시작과 중복 replay에서도 history를 중복 없이 복원하고, source
sequence가 wire payload에 없는 2.0.0 계약에서는 단일 partition만 허용한다. 기존 Redisson
bounded-read 경로는 별도 선택지로 유지한다.

## 2. 근거

- GNO `bluetape4k-github`: workshop Issue #893, `bluetape4k-javers` Issue #304와 merged PR #315.
- dependencies 2.0.0이 관리하는 JaVers 1.0.0 API: `VanillaKafkaCdoSnapshotRepository`, `KafkaCdoSnapshotProjector`,
  `KafkaCdoSnapshotProjectionOptions`, `replayUntilIdle`, `skipExistingSnapshots`.
- 현재 module은 Redis read path와 bounded history를 구현했지만 Kafka를 write-only 선택지로만
  설명한다.
- root `bluetape4k-dependencies` 2.0.0이 이미 versionless
  `bluetape4k-javers-persistence-kafka` alias를 관리한다.

## 3. 공개 예제 계약

`KafkaRedisOrderAuditFactory.create(...)`는 다음 경계를 묶은
`KafkaRedisOrderAuditPipeline`을 반환한다.

- `place`, `markPaid`, `delete`: 내부 Kafka-backed service에만 위임하는 command facade
- `getHistory`, `getLatestSnapshot`, `findCurrent`, `diff`: 내부 Redis-backed service에만 위임하는 query facade
- `replayUntilIdle(maxIdlePolls = 3)`: Kafka snapshot을 Redis로 재생하고 처리 결과를 반환
- `close()`: factory가 소유한 producer와 consumer를 닫는 idempotent lifecycle 경계

내부 writer/reader `OrderAuditService`는 외부에 노출하지 않는다. 따라서 write-only Kafka repository에서
실수로 `getHistory()`를 호출해 빈 결과를 정상 history로 오인할 수 없다.

caller는 topic, producer/consumer config, Redis repository name, Lettuce `RedisClient`를 제공한다.
topic과 repository name은 blank를 거부한다. consumer group은 config에서 명시하며 예제 factory가
운영 환경의 bootstrap server나 credential을 추정하지 않는다.

factory는 `group.id`가 nonblank인지 확인하고 `enable.auto.commit=false`,
`auto.offset.reset=earliest`를 강제한다. caller가 충돌하는 값을 제공하면 생성 시점에 거부한다.
이 세 설정은 batch 성공 후 수동 commit 및 restart catch-up 계약의 전제다.

factory는 config map으로 producer/consumer를 만드는 owned-resource 경로만 제공한다. caller-owned
`Producer`/`Consumer`를 직접 사용하는 세밀한 lifecycle 예시는 upstream API의 생성자를 사용하며,
workshop failure fixture는 이 경로로 poll/commit 호출을 관찰한다.

## 4. Projection 및 offset 계약

- projector는 첫 poll 전에 `partitionsFor(topic)`을 확인하며 partition 수가 정확히 1이 아니면
  projection 전에 실패한다.
- poll batch의 모든 record가 projection된 후에만 `commitSync()`가 호출된다.
- projection target은 snapshot과 commit sequence를 하나의 Redis `MULTI/EXEC`로 queue하는
  `LettuceCdoSnapshotRepository`로 제한한다. Redisson repository는 partial snapshot/head가 남을 수
  있으므로 projector target으로 사용하지 않는다.
- `EXEC` 이전 실패 또는 실행 전 `DISCARD` 성공에서는 snapshot/head가 반영되지 않고 consumer offset도
  commit되지 않는다. 이 pre-EXEC failure를 workshop fixture로 검증한다.
- Redis `EXEC`은 rollback을 제공하지 않는다. `EXEC` 이후 개별 command error 또는 연결 단절은
  commit-unknown/부분 반영 가능 상태이며 provider가 transaction result의 개별 error를 검증하지 않는
  현재 계약에서 all-or-nothing으로 주장하지 않는다. offset 미커밋 후 same-group replay와
  `skipExistingSnapshots`가 정상적으로 저장된 snapshot을 조정하지만 모든 partial metadata 손상을
  자동 복구한다는 보장은 없다.
- batch 앞부분이 성공하고 후속 record의 pre-EXEC target failure가 발생하면 앞부분 snapshot은 남지만
  offset은 commit되지 않는다. 실패한 consumer/projector를 닫고 같은 `group.id`의 새 consumer/projector를
  만들면 committed offset부터 batch가 다시 전달된다. `skipExistingSnapshots=true`가 앞부분을 건너뛰고
  실패 record를 다시 처리한 뒤에만 offset을 commit한다. 동일 consumer의 position이 자동 rewind된다고
  가정하지 않는다. 이는 exactly-once가 아니라 idempotent at-least-once 복구 계약이다.
- 같은 stream을 새 consumer group으로 replay해도 commit id와 version이 같은 snapshot은 건너뛰고
  Redis history에 중복을 만들지 않는다.
- `subscribe()` 직후 group assignment가 준비되기 전 첫 poll이 비어 있을 수 있으므로 facade 기본값은
  연속 idle poll 3회다. 첫 empty poll 뒤 record가 도착하는 fixture를 두며, 운영 caller는 broker와
  poll timeout에 맞는 idle 횟수 및 전체 deadline을 선택한다.

## 5. 재시작과 bounded history

빈 Lettuce Redis repository와 새 consumer group으로 pipeline을 다시 만들고 `replayUntilIdle()`을 실행하면
Kafka에 남아 있는 snapshot stream으로 history를 복원한다. 복원 결과는 기존
`OrderAuditService.getHistory(id, limit)`의 `1..100`, newest-first, bounded decode 계약을 그대로
사용한다. 단, Lettuce projection 경로의 response limit은 저장소 전체 decode 비용을 제한하지 않는다.
실제 Redis range/decode bound가 필요한 조회는 기존 Redisson 예제를 사용한다. Kafka repository
자체에서는 history를 조회하지 않는다.

## 6. 테스트 전략

- 실제 Kafka/Redis Testcontainers에서 initial/update snapshot을 publish하고 projection한다.
- `limit=1/2` bounded history와 latest snapshot이 projected target에서 조회되는지 검증한다.
- 다른 consumer group으로 같은 stream을 replay해 projected count 0, skipped count 2,
  history size 2이며 성공 후 offset이 commit되는지 검증한다.
- 새 Redis repository와 새 group의 restart/replay가 history를 재구성하는지 검증한다.
- multi-partition topic을 AdminClient로 만들고 `poll()`/`commitSync()` 0회, target head 불변 상태로
  첫 projection 전에 거부하는지 검증한다.
- mutation 전에 실패하는 target fixture와 caller-owned consumer로 pre-EXEC 동등 failure를 만들고
  projection 실패 시 `commitSync()` 미호출,
  target head 불변을 검증한다. 이어 실패 consumer/projector를 닫고 같은 group의 새 instance를 생성해
  committed offset부터 record가 다시 전달되어 성공 후에만 commit되는지 확인한다.
- lifecycle close는 consumer, producer, Lettuce repository connection을 각각 정확히 한 번 정리한다.
  한 resource close가 실패해도 나머지를 계속 정리하고 최초 예외에 후속 예외를 suppressed로 보존한다.
  caller-owned `RedisClient`는 닫지 않는다.
- blank topic/repository name, non-positive poll timeout/idle poll을 거부한다.
- blank group id와 auto-commit/offset-reset 충돌 설정을 생성 시점에 거부한다.

## 7. 성능·안정성·보안

- `replayUntilIdle`은 finite catch-up 전용이다. continuous stream daemon으로 사용하지 않으며 caller는
  bounded poll timeout과 coroutine/future/process deadline을 적용한다. workshop test도 전체 timeout을 둔다.
- duplicate 확인은 record마다 해당 global id의 history를 읽으므로 긴 history용 scalable index가 아니다.
  이 예제는 bounded audit fixture에 한정하고 대량 projection은 별도 upstream index 설계를 요구한다.
- 진단에는 topic/partition/offset과 unsalted SHA-256 key fingerprint만 포함한다. fingerprint는 익명화나
  secret이 아니라 correlation hint이며, raw global id, snapshot payload, Redis key, Kafka config/credential을
  로그에 추가하지 않는다.
- Kafka payload는 domain field를 포함할 수 있다. broker TLS/SASL/ACL, topic retention/encryption과
  reader의 tenant authorization/redaction은 caller 책임이며 config map은 producer/consumer에 그대로 전달한다.
- Kafka exactly-once, Redis `EXEC` rollback/commit-unknown 자동 복구, cross-store transaction,
  multi-partition ordering, global source sequence 설계는 이 workshop 범위에서 제공하지 않는다.

## 8. 의존성과 운영 가드

- root `platform(libs.bluetape4k.dependencies)` 2.0.0만 bluetape4k version authority로 사용한다.
- module은 versionless `libs.bluetape4k.javers.persistence.kafka`, `libs.bluetape4k.lettuce`,
  direct API surface용 `libs.kafka.clients`와 `libs.lettuce.core`를 추가한다. 개별 bluetape4k BOM,
  explicit bluetape4k version이나 미래 snapshot version을 사용하지 않는다. Kafka Testcontainers server는
  existing `libs.bluetape4k.testcontainers`를 사용하며 JaVers Kafka와 Kafka client 두 artifact를
  dependency insight로 확인한다.
- 기존 module이 이미 smoke/full group에 등록되어 있으므로 membership은 유지한다.
- 양 언어 README, root README pair, coverage matrix, ecosystem reuse manifest, stale-check와 lesson을
  같은 branch에서 갱신한다. `.github/workflows/Examples.yml`의 기존 path filter와 smoke/full task는
  structural check로 유지 여부를 증명하고 실제 membership 변경이 없으면 no-op으로 기록한다.
- README와 lesson은 최초 persistence-audit module을 도입한 Issue #290의 후속임을 명시하고
  stale-check에서 참조를 고정한다.

## 9. 완료 조건

- 설계·계획·구현·PR 리뷰의 P0/P1 0건
- RED regression 증거 후 Testcontainers integration과 기존 module test 통과
- root detekt, stale/ecosystem/README/actionlint/diff 계약 통과
- dependency insight에서 JaVers Kafka artifact가 dependencies 2.0.0 constraint로 resolve
- PR exact-head hosted CI 전체 통과, milestone 2.0.0, assignee debop, merge 전 사용자 최종 승인
