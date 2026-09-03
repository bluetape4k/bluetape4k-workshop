# Issue #875 DynamoDB Streams Flow·checkpoint 소비자 예제 교훈

## 배경

기존 `aws/ktor-dynamodb` 예제는 주문 세션 테이블의 CRUD와 TTL만 확인하고
변경 이벤트를 소비하지 않았다. bluetape4k 2.0.0의 AWS Kotlin Streams 확장은
shard를 coroutine `Flow`로 발견·poll하고 시작 위치와 checkpoint store를
소비자 경계에서 선택할 수 있게 한다.

## 결정

- 테이블을 자동 생성할 때 `NewAndOldImages` stream을 켜고, 기존 local fixture와
  실제 AWS 테이블은 Streams 활성화 여부를 명시적으로 요구한다. 기능은
  `bluetape4k.aws.dynamodb.streams.enabled=true`일 때만 client와 route를 등록한다.
- `DynamoDbStreamsOrderSessionService`는 `TRIM_HORIZON` 또는 `LATEST`에서
  `shardRecordFlow`를 수집하고 `maxRecords`, poll/empty backoff,
  `maxShardConcurrency`로 bounded 소비를 유지한다. Ktor route는
  `/dynamodb/order-sessions/streams/consume`에서 동일한 경계를 노출한다.
- 레코드 processor가 성공한 뒤에만 `DynamoDbStreamsCheckpointStore.save`를
  호출한다. processor 실패나 cancellation이면 checkpoint를 전진시키지 않아
  재개 시 같은 레코드를 다시 받을 수 있고, 재전달은 shard·sequence 기준으로
  `duplicate` 메타데이터에 표시한다. 이는 at-least-once 예제이며 exactly-once나
  영속 checkpoint DB를 약속하지 않는다.
- Floci 통합 테스트는 stream-enabled order-session fixture에서 첫 consume의
  checkpoint와 다음 inclusive resume의 duplicate를 확인한다. 단위 테스트는
  processor 실패, empty stream, `close()` idempotency를 고정한다.

## 검증

- `./gradlew :aws-ktor-dynamodb:test`: Ktor route, service, local configuration
  테스트 통과.
- `./gradlew build -x test` 및 `./gradlew detekt`: consumer BOM/정적 분석 통과.
- `scripts/smoke-validate.sh stale-check`: Streams config/service/test, 양국
  README, root 문서, coverage matrix, lesson guard 통과.

## 다음 guard

DynamoDB Streams의 checkpoint는 예제 기본값인 in-memory store에만 남는다. 운영
소비자는 durable store의 conditional write와 shard lease/fencing, 재시작 시
checkpoint 보존, payload 민감정보 정책을 별도로 설계해야 한다. bounded Flow를
무제한 `toList()`나 exactly-once 보장으로 확장하지 않는다.
