# Usage Billing Invoice Service

[English](README.md) | 한국어

`usage-billing-invoice-service`는 Billing event로부터 불변 invoice line을
materialize합니다. `billing.events.v1`을 소비하고 `ChargeRated` 또는
`AdjustmentPosted`마다 line을 append한 뒤, 기존 line을 수정하지 않고 invoice
event를 발행합니다.

![Usage billing 서비스 경계](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.svg)

## 책임

- producer DTO를 공유하지 않고 Billing wire envelope를 로컬에서 decode합니다.
- `(tenantId, eventId, payloadDigest)`로 deduplicate합니다.
- source event ID와 선택적인 `correctionOf`를 가진 `InvoiceLine`을 append합니다.
- local outbox를 통해 `InvoiceIssued` 또는 `InvoiceCorrectionIssued`를
  발행합니다.

HTTP controller는 없습니다. `InvoiceInboxService`와 `InvoiceJournal`이
서비스 test가 사용하는 application 및 persistence 경계입니다.

## inbound contract

`BillingChargeDecoder`는 schema `1`과 `ChargeRated`, `AdjustmentPosted` event
type을 허용합니다. 로컬 `InvoiceInboxEvent`를 만들기 전에 payload digest를
검증합니다.

| 결과 | 동작 |
| --- | --- |
| 새 event | 불변 invoice line 하나와 outbox event 하나를 append |
| 같은 event ID와 같은 digest | 새 line 없이 `DUPLICATE` 반환 |
| 같은 event ID와 다른 digest | correctness conflict로 `QUARANTINED` 반환 |
| `correctionOf`가 있는 `AdjustmentPosted` | 원래 line을 보존하고 새 correction line append |

`InvoiceLines` repository는 append-only입니다. history를 다시 쓰는 `save`와
`delete`는 거부됩니다. outbox 전달은 `PENDING → CLAIMED → PUBLISHED`를 따르며
`RETRY_WAIT`, `QUARANTINED` 복구 상태와 lease fencing 갱신을 포함합니다.

관련 source: [`InvoiceInboxService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/invoice/application/InvoiceInboxService.kt),
[`InvoiceJournal`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/invoice/domain/InvoiceInbox.kt),
[`BillingChargeConsumer`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/invoice/messaging/BillingChargeConsumer.kt),
[`InvoiceOutboxPersistence`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/invoice/persistence/InvoiceOutboxPersistence.kt).

## 검증 실행

```bash
./gradlew :commerce-usage-billing-invoice-service:test --max-workers=1
./gradlew :commerce-usage-billing-invoice-service:integrationTest --max-workers=1
```

기본 suite는 container 없이 실행합니다. PostgreSQL integration test는 inbox
uniqueness, append-only line, correction materialization, local outbox 원자적
기록, publisher fencing을 검증합니다.

## 관련 서비스

- [`usage-billing-billing-service`](../usage-billing-billing-service/)가 여기서
  소비할 charge와 adjustment event를 발행합니다.
- [`usage-billing-query-service`](../usage-billing-query-service/)가 invoice
  event를 tenant 및 operator 조회용으로 projection합니다.
