# Issue #874 SQS Observation listener 소비자 예제 교훈

## 배경

기존 `aws/sqs-sns-coroutines` 예제는 one-shot `SqsOperations.receive`와 자체
metrics만 사용해 Spring SQS listener의 receive/process/ack parentage와 coroutine
trace context 전파를 확인할 수 없었다. bluetape4k 2.0.0의 SQS listener container는
Micrometer ObservationRegistry, 수동 acknowledgement, visibility heartbeat,
cancellation cleanup을 선택적으로 제공한다.

## 결정

- `SqsObservationExampleConfiguration`은 `bluetape4k.aws.sqs.observation.enabled=true`
  이고 `ObservationRegistry`가 있을 때만 등록한다. 기본값은 false이며, 기존
  `consumeOnce` 경계와 retry/redelivery 분류를 변경하지 않는다.
- `OrderNotificationObservationListener`는 `autoStartup=false`인 `@SqsListener`로
  유지해 실습자가 registry에서 명시적으로 시작하게 한다. process observation이
  current인 동안 child observation을 만들고, handler 성공 후에만 수동 ack를 호출한다.
- listener annotation은 1초 heartbeat 간격과 3초 visibility를 사용한다. 실제
  로컬 adapter는 visibility 만료와 변경 이력을 추적해 중복 poll을 막고 heartbeat를
  관찰 가능하게 한다.
- recorder는 stage, outcome, attempt, delivery, acknowledgement action만 보존한다.
  message body, receipt handle, 전체 queue URL은 telemetry 상태에 저장하지 않는다.
  `CancellationException`은 다시 던지며, `ObservationRegistry.NOOP`에서는 listener가
  동작하되 observation을 만들지 않는다.
- durable DLQ, exactly-once 보장, global tracing backend 설치, 기본 AWS credential은
  범위 밖에 둔다.

## 검증

- `SqsObservationExampleTest`: 기본 disabled, 활성 process parentage와 child
  observation, heartbeat 및 ack, NOOP registry, handler cancellation을 검증한다.
- `./gradlew :aws-sqs-sns-coroutines:test --tests '*SqsObservationExampleTest'`
  결과 4개 테스트 통과.
- `scripts/smoke-validate.sh stale-check`에 설정·listener·local visibility·테스트·양국
  README·lesson guard를 추가했다.

## 다음 guard

Observation을 켜도 외부 tracing backend나 durable DLQ가 자동으로 구성되는 것은 아니다.
실제 운영에서는 registry handler의 cardinality·민감정보 정책과 queue redrive 정책을
별도로 검토하고, heartbeat가 실패한 경우의 visibility·재처리 예산을 명시해야 한다.
