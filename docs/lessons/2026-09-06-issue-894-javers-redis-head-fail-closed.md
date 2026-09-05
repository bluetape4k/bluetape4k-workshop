# Issue #894 JaVers Redis head metadata fail-closed

## Context

dependencies 2.0.0의 JaVers Redis repository는 startup에서 malformed sequence와 commit-id를
sanitized exception으로 거부한다. 기존 persistence-audit factory는 repository를 만들면서 head를 읽지 않아
corrupted namespace에서도 service나 Kafka resource를 먼저 만들 수 있었다.

## Decision or Finding

- Redisson과 Lettuce factory는 외부 resource를 반환하거나 Kafka producer/consumer를 만들기 전에 `getHeadId()`를
  호출한다.
- Head가 없으면 documented provider snapshot-index key의 존재를 O(1)로 확인한다. Snapshot index가 남아 있으면
  metadata 유실로 보고 generic integrity error로 거부한다.
- Provider의 safe diagnostic만 전파하며 raw commit-id, sequence, repository name, Redis key, domain id를 추가하지 않는다.
- `getHeadId()`는 instance에서 cache되므로 query마다 새 repository를 만드는 O(N) scan은 사용하지 않는다.

## Outcome

정상 restart는 기존 head를 이어가고 truly empty namespace는 초기 상태로 시작한다. Malformed head와
snapshot-only partial loss는 service/pipeline 생성 전에 fail-closed되며 Kafka resource도 열리지 않는다.

## Verification

- Redisson malformed commit-id와 snapshot-only metadata loss Testcontainers 회귀
- Lettuce malformed sequence와 snapshot-only metadata loss Testcontainers 회귀
- Invalid Kafka producer config보다 Redis integrity error가 먼저 발생하는 startup-order 회귀
- Normal history/latest, bounded decode, Kafka projection, sink failure 회귀

## Future Guidance

실행 중 외부 metadata 변조 감지가 필요하면 provider에 O(1) persisted head/version과 explicit fresh-validation API를
설계한다. Workshop query마다 repository를 재생성하거나 전체 sequence hash를 scan하지 않는다.
