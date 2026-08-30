# Issue #864 Kinesis consumerFlow checkpoint/lease 경계

## Context

기존 `aws/kinesis-coroutines` 예제는 `KinesisOperations.recordFlow`를 사용해 한
샤드의 publish/consume만 보여주었다. `bluetape4k-aws-java`의 AWS SDK v2
`consumerFlow`는 `ListShards` discovery, 샤드별 순차 polling, bounded
`maxShardConcurrency`, caller-owned checkpoint/lease SPI를 제공한다.

## Decision or Finding

- 기존 `LocalKinesisOperations`는 publish/ensure 회귀를 위해 한 샤드 계약을
  유지하고, 별도의 `LocalKinesisConsumerClient`가 두 샤드와 AWS SDK async
  interface만 제공한다. local 경로에는 credential, 네트워크, AWS provisioning이
  없다.
- consumer 식별자는 `consumerGroup`, `streamIdentity`, `ownerId`로 명시하고,
  local 기본값은 `InMemoryKinesisCheckpointStore`와
  `InMemoryKinesisLeaseStore`를 사용한다. 이 저장소는 restart/durable 운영 저장소가
  아니다.
- upstream flow의 rendezvous handoff 뒤 downstream `emit`이 반환된 다음에만
  sequence checkpoint를 저장한다. lease counter fencing은 stale owner의
  checkpoint overwrite와 release를 막는다.
- upstream snapshot의 cancellation 경계에서 heartbeat join이 release보다 먼저
  취소될 수 있으므로, workshop service는 collection별 tracking lease adapter를
  두고 `NonCancellable` cleanup에서 잔여 lease를 회수한다. 실제 AWS client와
  caller-owned store의 close는 이 예제가 소유하지 않는다.
- delivery semantics는 at-least-once이며 shard 간 global ordering이나 exactly-once를
  주장하지 않는다. durable Redis/DynamoDB adapter와 AWS provisioning은 별도
  후속 범위다.

## Outcome

local profile은 기존 deterministic publish/ensure와 두 샤드 consumer discovery,
샤드별 ordering, bounded concurrency, backpressure, checkpoint-after-emit,
lease fencing을 credential 없이 실행할 수 있다. `real-aws` profile은 upstream
auto-configuration이 제공하는 `KinesisAsyncClient`를 명시적 opt-in으로 사용하며
`ListShards` 권한이 필요하다.

## Verification

- `./gradlew :aws-kinesis-coroutines:compileKotlin --no-daemon --max-workers=1`
- `./gradlew :aws-kinesis-coroutines:test --tests '*KinesisConsumerFlowTest' --no-daemon --max-workers=1`
- `./gradlew :aws-kinesis-coroutines:test --tests '*KinesisAutoConfigurationTest' --no-daemon --max-workers=1`
- `./gradlew :aws-kinesis-coroutines:test --no-daemon --max-workers=1`
- `git diff --check`
- local `bootRun`은 credential 없이 `publishedCount=3`, `consumedCount=3`을
  출력하고 exit code `0`이어야 한다.

## Future Guidance

새 consumer 예제를 확장할 때도 `consumerFlow`의 caller-owned lifecycle,
checkpoint-after-emit, lease fencing, at-least-once semantics를 문서와 테스트에서
함께 유지한다. durable store를 추가할 경우 lease와 checkpoint의 consistency
domain, conditional write, restart/replay 정책을 먼저 별도 issue로 설계한다.
