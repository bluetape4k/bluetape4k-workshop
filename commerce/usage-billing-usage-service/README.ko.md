# Usage Billing Usage Service

[English](README.md) | 한국어

`usage-billing-usage-service`는 Meter 가격 근거가 로컬에 도착한 뒤 usage
fact를 접수합니다. source event identity와 승인된 가격 provenance를 durable
하게 저장한 후 `usage.events.v1`에 `UsageAccepted`를 발행합니다.

![Usage billing 서비스 경계](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.svg)

## 책임

- `meter.events.v1`의 `PriceActivated`를 로컬 가격 근거로 소비합니다.
- `(tenantId, sourceSystem, sourceEventId)`로 usage를 deduplicate합니다.
- tenant, meter, currency가 일치하는 가격 근거가 있고 quantity가 양수인 경우만
  접수합니다.
- usage fact와 `PENDING` outbox row를 하나의 PostgreSQL transaction으로
  기록합니다.

HTTP controller는 없습니다. `UsageCommandService`가 application 경계이며,
Kafka listener와 outbox publisher는 서로 분리된 전달 경계입니다.

## usage 접수

`AcceptUsageCommand`는 비어 있지 않은 tenant, source, event, meter, currency
식별자와 양수 quantity를 요구합니다. service는 로컬 `PriceEvidence`의 unit
price를 기록하며 Meter 데이터베이스를 동기적으로 읽지 않습니다.

| 결과 | 동작 |
| --- | --- |
| 가격 근거가 있고 source event가 새 값인 경우 | `UsageRecord`, envelope schema `1`, outbox row 저장 |
| 같은 source identity와 같은 fingerprint | 기존 접수를 replay 결과로 반환 |
| 같은 source identity와 다른 fingerprint | `UsageSourceConflict`로 거부 |
| 로컬 가격 근거가 없음 | `MissingPriceEvidence`로 거부 |

발행하는 envelope에는 Billing이 자체 복제 근거로 rate할 수 있도록 승인된
가격 provenance가 들어 있습니다.

## 가격 근거 inbox

Meter listener는 local evidence를 저장하기 전에 event type `PriceActivated`,
schema `1`, 필수 field와 payload digest를 검증합니다. 결과는 `APPLIED`,
`DUPLICATE`, `QUARANTINED`입니다. 중복 전달은 안전하게 처리하지만 digest
충돌이나 잘못된 contract를 조용히 적용하지 않습니다.

usage outbox는 `PENDING → CLAIMED → PUBLISHED` 상태를 따르며 transport
failure는 `RETRY_WAIT`, 소진되었거나 유효하지 않은 record는 `QUARANTINED`로
이동합니다. publisher는 Kafka transport를 최대 5초 기다리고 lease로 최종
상태 갱신을 fencing합니다.

관련 source: [`UsageCommandService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/usage/application/UsageCommandService.kt),
[`PriceEvidenceService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/usage/application/PriceEvidenceService.kt),
[`UsageIntegrationEnvelope`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/usage/integration/UsageIntegrationEnvelope.kt),
[`UsageOutboxPersistence`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/usage/persistence/UsageOutboxPersistence.kt).

## 검증 실행

```bash
./gradlew :commerce-usage-billing-usage-service:test --max-workers=1
./gradlew :commerce-usage-billing-usage-service:integrationTest --max-workers=1
```

기본 suite는 container 없이 command, decoder, envelope, idempotency, publisher,
repository contract를 검증합니다. integration suite는 PostgreSQL uniqueness,
로컬 evidence, usage/outbox 원자적 기록, replay를 검증합니다.

## 관련 서비스

- [`usage-billing-meter-service`](../usage-billing-meter-service/)가 이 서비스가
  필요로 하는 가격 근거를 발행합니다.
- [`usage-billing-billing-service`](../usage-billing-billing-service/)가
  `UsageAccepted`를 소비하고 로컬에서 charge를 산정합니다.
