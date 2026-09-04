# Issue #876 — Spring Modulith SNS/SQS externalization 소비자 경계

## 배경

`bluetape4k-dependencies:2.0.0`에서 추가된 AWS Spring Modulith event
externalization을 기존 `aws/sqs-sns-coroutines` 예제에 연결했다. 목적은
domain event를 외부 메시지 계약으로 바꾸는 지점과, SQS 소비 성공 이후에만
acknowledgement를 수행하는 지점을 한눈에 보여 주는 것이다.

## 적용한 경계

- `ModulithOrderPlacedEvent`는 `order.placed` type과 version 1을 가진
  `ModulithOrderPlacedIntegrationEvent`로만 외부화한다.
- `privateNote`와 원문 correlation identifier는 외부 envelope에서 제거한다.
  correlation 값은 16자리 SHA-256 prefix header로 제한한다.
- logical target은 `order-notifications` 하나로 고정하고, destination이
  `.fifo`로 끝날 때만 order id를 FIFO message-group key로 사용한다.
- `publish`는 Spring Modulith transport future가 정상 완료된 뒤에만
  `PUBLISHED`를 반환한다. 취소는 다시 던지고, 그 밖의 transport 예외는
  `FAILED`로 제한해 보고한다.
- `consumeOnce`는 public `AwsModulithSqsEventConsumer`로 dispatch한 뒤에만
  SQS delete를 호출한다. handler 실패, 알 수 없는 type/version, malformed
  envelope, partial 처리 실패는 visibility를 0으로 바꾸고 `RETRY_REQUESTED`로
  반환한다.

## 검증

다음 테스트가 local SQS adapter에서 실행된다.

```bash
./gradlew :aws-sqs-sns-coroutines:test \
  --tests '*ModulithExternalizationExampleTest'
```

검증 항목은 fixture 기본 disabled, 민감정보 redaction, transport 완료 후
publish 성공, 정상 dispatch 후 ack, handler 실패 시 visibility retry, FIFO
group/dedup key 보존이다. 실제 AWS 자격 증명이나 durable exactly-once 저장소는
사용하지 않으며, Floci 통합 경로에서 AWS 호환 wiring을 확인한다.

## 남은 확장 지점

실서비스에서는 redrive policy/DLQ, durable idempotency store, partial publish
재조정, trace backend 연동을 운영 계약으로 추가해야 한다. 이 예제는 그 경계를
숨기지 않고 `2.0.0` 소비자 학습 경로에 남겨 둔다.
