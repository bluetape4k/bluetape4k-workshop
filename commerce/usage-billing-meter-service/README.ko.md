# Usage Billing Meter Service

[English](README.md) | 한국어

`usage-billing-meter-service`는 usage-billing 마이크로서비스 예제의 가격
권위입니다. 불변 가격 버전을 기록하고 `PriceActivated` integration event를
발행하며, Usage·Billing·Invoice·Query의 데이터베이스를 공유하지 않습니다.

![Usage billing 서비스 경계](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.svg)

## 책임

- tenant, meter, currency, unit price, effective time을 포함한
  `ActivatePriceCommand`를 받습니다.
- `(tenantId, idempotencyKey)`를 command identity로 취급합니다.
- 불변 `MeterPriceVersion`과 durable command receipt를 저장합니다.
- local outbox를 통해 `meter.events.v1`에 `PriceActivated`를 발행합니다.

이 모듈에는 HTTP controller가 없습니다. `MeterCommandService`가 contract,
persistence, composition test가 사용하는 application 경계입니다.

## 활성화 contract

`MeterCommandService.activatePrice`는 비어 있지 않은 식별자와 양수
`unitPrice`를 검증합니다. command field를 SHA-256으로 hash한 뒤 다음을
기록합니다.

| 결과 | 동작 |
| --- | --- |
| 첫 command | price version `1`, envelope schema `1`, `PENDING` outbox row 생성 |
| 같은 idempotency key와 같은 fingerprint | 두 번째 price version 없이 기존 결과 재생 |
| 같은 idempotency key와 다른 fingerprint | `MeterIdempotencyConflict`로 거부 |

envelope에는 `meterCode`, `currency`, `unitPrice`와 독립적인 payload digest가
들어 있습니다. local transaction은 command receipt, price version, envelope,
outbox row를 함께 commit합니다.

## 전달과 복구

`MeterOutboxPublisher`는 owner-and-lease 조건으로 row를 claim하고
`meter.events.v1`에 발행한 뒤 같은 ownership token으로 `markPublished`를
fencing합니다. durable state machine은 다음과 같습니다.

`PENDING → CLAIMED → PUBLISHED`

transport failure가 발생하면 claimed row는 `RETRY_WAIT`로 이동합니다. 시도
횟수를 소진했거나 유효하지 않은 record는 `QUARANTINED`가 될 수 있습니다.
Kafka가 record를 받은 뒤 local status를 갱신하기 전에 process가 중단되어도,
수신 서비스가 중복 전달로 처리하므로 복구할 수 있습니다.

관련 source: [`MeterCommandService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/meter/application/MeterCommandService.kt),
[`MeterIntegrationEnvelope`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/meter/integration/MeterIntegrationEnvelope.kt),
[`MeterOutboxPersistence`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/meter/persistence/MeterOutboxPersistence.kt).

## 검증 실행

기본 test suite는 container 없이 실행합니다. PostgreSQL과 Kafka integration
test는 Testcontainers를 사용하며 저장소의 `TestMutexService` 규칙에 따라
직렬로 실행해야 합니다.

```bash
./gradlew :commerce-usage-billing-meter-service:test --max-workers=1
./gradlew :commerce-usage-billing-meter-service:integrationTest --max-workers=1
```

## 관련 서비스

- [`usage-billing-usage-service`](../usage-billing-usage-service/)가 가격 근거를
  소비하고 usage를 접수합니다.
- [`usage-billing-microservices-composition-tests`](../usage-billing-microservices-composition-tests/)
  가 다섯 서비스의 지연 발행, replay, 복구를 함께 검증합니다.
