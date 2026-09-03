# Issue #873 SNS PublishBatch 소비자 예제 교훈

## 배경

기존 `aws/sqs-sns-coroutines` 예제는 주문 알림 하나마다
`SnsPublishRequest`를 호출했다. bluetape4k 2.0.0의 Spring SNS 계약은
`SnsPublishBatchRequest`와 typed `successful`/`failed` 결과, bounded transport
metadata를 제공하므로 기존 단건 경계를 깨지 않고 소비자 예제로 확장했다.

## 결정

- 서비스 API는 빈 목록을 허용하지 않고 최대 10개 주문만 받는다. SNS entry ID는
  trim한 `idempotencyKey`로 고정해 중복 ID를 호출 전에 거부한다.
- 단건 `publish`는 그대로 유지하고, batch 응답은 성공·entry별 실패를 각각
  `OrderNotificationBatchEntryReport`로 매핑한다. 응답 순서는 upstream 계약이
  보장하는 입력 순서를 그대로 사용한다.
- transport/protocol 실패는 자동 재시도하지 않는다. 이미 완료된 ID와 응답을
  받지 못한 ID만 bounded metadata로 남겨 호출자가 외부 idempotency 정책에 따라
  조정하도록 한다. durable DLQ, exactly-once, 보상 트랜잭션은 범위 밖이다.
- 기본 local adapter는 batch request를 캡처하고 모든 entry를 성공시키며, Floci
  테스트는 실제 `SnsCoroutinesTemplate.publishBatch` 경계를 실행한다.

## 검증

- 단위 테스트: 최대 10개, 빈 목록, 빈 payload, 중복 ID, partial response,
  transport 완료/미확정 ID, cancellation 전파.
- Floci 통합 테스트: 두 주문의 `PublishBatch` 성공 결과와 message ID 매핑.
- `scripts/smoke-validate.sh stale-check`: 서비스·모델·테스트·양국 README·root
  README·lesson 등록 guard.

## 다음 guard

SNS PublishBatch가 성공 응답을 반환해도 비즈니스 보상이나 exactly-once를
보장하지 않는다. 애매한 transport 결과를 재전송할 때는 entry ID와 외부
idempotency 저장소를 기준으로 조정해야 하며, 무제한 자동 retry를 추가하지 않는다.
