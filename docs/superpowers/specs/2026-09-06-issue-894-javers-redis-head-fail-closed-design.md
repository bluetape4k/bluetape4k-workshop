# Issue #894 Redis head metadata fail-closed 설계

## 목표

`exposed/javers-persistence-audit`의 Redisson과 Kafka→Lettuce Redis factory가 dependencies 2.0.0의
malformed head metadata 보호를 startup/rebuild 경계에서 적용하도록 한다. Redis sequence hash가 없거나
비어 있고 Order snapshot도 없을 때만 초기 상태로 허용한다. 존재하는 sequence 또는 commit-id를 해석할 수
없거나 head 없이 snapshot만 남았으면 service/pipeline을 반환하지 않고 `IllegalStateException`으로 실패한다.

## 근거와 범위

- GNO와 live GitHub의 workshop Issue #894, `bluetape4k-javers` Issue #334와 merged PR #345를 기준으로 한다.
- provider의 `LettuceCdoSnapshotRepository`는 missing sequence를 `0L`, 빈 head를 `null`로 처리하고 malformed
  sequence/commit-id는 `type`, SHA-256 fingerprint 16자리, length만 포함한 예외로 거부한다.
- `RedissonCdoSnapshotRepository`는 malformed commit-id를 같은 형식으로 거부한다. typed `LongCodec` 경로의
  malformed Redisson sequence는 provider의 공개 보장 범위가 아니므로 workshop에서 확대 주장하지 않는다.
- controller/ProblemDetail surface는 이 module에 없으므로 repository와 facade 예외 메시지 비노출을 검증한다.

## 소비자 경계

- `RedisOrderAuditFactory`는 service를 반환하기 전에 head를 한 번 검증한다. restart 시 corrupted head를 정상
  초기 상태로 오인하지 않는다.
- `KafkaRedisOrderAuditFactory`도 producer/consumer 생성 전에 Lettuce head를 검증해 corrupted state에서 외부
  자원을 열지 않는다.
- provider의 `getHeadId()`는 최초 load 후 인스턴스에 cache된다. query마다 새 repository를 만들어 전체 sequence
  hash를 O(N) scan하는 우회는 bounded history 성능을 훼손하므로 실행 중 외부 손상 감지는 약속하지 않는다.
- head가 `null`이면 provider의 documented Redis key schema를 캡슐화한 O(1) key-existence probe로 snapshot index
  존재 여부를 startup에서 한 번 확인한다. Redisson은 `javers:<name>:snapshot`, Lettuce는
  `javers:<name>:globalId:set`을 확인한다. snapshot index가 남아 있으면 metadata 유실로 판단해 generic 예외로
  거부하고, truly empty namespace만 초기 상태로 허용한다.
- public JaVers `byClass(Order)` query는 limit 적용 전에 모든 snapshot을 materialize하므로 integrity probe로
  사용하지 않는다. provider key schema 변경은 Testcontainers compatibility test가 감지한다.
- 검증은 raw commit-id, sequence, repository name, Redis key, order/customer/payload를 새 로그나 예외에 추가하지
  않고 provider 예외를 그대로 전파한다.

## 테스트

- fresh Redisson repository의 missing metadata에서 empty history/latest가 유지된다.
- valid Redisson history를 만든 뒤 raw-sensitive malformed commit-id를 주입하면 factory rebuild가 fail-closed되고
  메시지는 `type=commitId`, fingerprint, length만 노출한다.
- snapshot은 남기고 head metadata를 삭제하면 factory rebuild가 generic integrity error로 실패한다.
- Lettuce repository에 malformed sequence를 주입하면 Kafka pipeline factory가 `type=sequence`로 거부되고 raw
  sequence, Redis key와 domain identifier를 노출하지 않는다.
- corrupted Lettuce state에서 factory가 Kafka producer/consumer 생성 전에 실패한다.
- 기존 bounded decode, normal restart, Kafka projection, audit sink failure 회귀를 유지한다.

## 의존성·운영

- root `bluetape4k-dependencies` 2.0.0만 version authority로 유지하며 새 dependency를 추가하지 않는다.
- 기존 smoke/full workflow membership은 변경하지 않고 structural guard로 검증한다.
- module README pair, root README pair, coverage matrix, ecosystem reuse manifest, stale-check와 lesson을 같은 branch에서
  갱신한다.

## 제외

- 실행 중 외부 metadata 변조 감지, Redis metadata 자동 복구·reset·migration, cross-region reconciliation,
  Redisson raw malformed sequence 진단, controller ProblemDetail, Kafka replay 기반 repair는 포함하지 않는다.

## 완료 조건

- 설계·구현 리뷰 P0/P1 0건
- RED 회귀 후 module clean test, detekt, smoke/full/stale/ecosystem/README/actionlint/diff 검증 통과
- dependencies 2.0.0 resolution 확인
- PR exact-head hosted CI와 metadata 확인 후 다섯 PR 전체에 대한 최종 병합 승인 요청
