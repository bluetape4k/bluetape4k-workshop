# Usage Billing Invoice Service

[한국어](README.ko.md) | English

`usage-billing-invoice-service` materializes immutable invoice lines from
Billing events. It consumes `billing.events.v1`, appends a line for each
`ChargeRated` or `AdjustmentPosted` event, and publishes an invoice event without
editing an earlier line.

![Usage billing service boundaries](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.svg)

## Responsibility

- decode Billing-owned wire envelopes locally, without sharing producer DTOs;
- deduplicate by `(tenantId, eventId, payloadDigest)`;
- append an `InvoiceLine` with the source event ID and optional `correctionOf`;
- emit `InvoiceIssued` or `InvoiceCorrectionIssued` through a local outbox.

There is no HTTP controller. `InvoiceInboxService` and `InvoiceJournal` are the
application and persistence boundaries used by the service tests.

## Inbound contract

`BillingChargeDecoder` accepts schema `1` and event types `ChargeRated` and
`AdjustmentPosted`. It verifies the payload digest before creating the local
`InvoiceInboxEvent`.

| Outcome | Behavior |
| --- | --- |
| new event | append one immutable invoice line and one outbox event |
| same event ID and same digest | return `DUPLICATE` without another line |
| same event ID and different digest | return `QUARANTINED` as a correctness conflict |
| `AdjustmentPosted` with `correctionOf` | append a new correction line; preserve the original line |

The `InvoiceLines` repository is append-only: `save` and `delete` operations
that would rewrite history are rejected. Outbox delivery follows
`PENDING → CLAIMED → PUBLISHED`, with `RETRY_WAIT` and `QUARANTINED` recovery
states and a fenced lease update.

Relevant source: [`InvoiceInboxService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/invoice/application/InvoiceInboxService.kt),
[`InvoiceJournal`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/invoice/domain/InvoiceInbox.kt),
[`BillingChargeConsumer`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/invoice/messaging/BillingChargeConsumer.kt),
and [`InvoiceOutboxPersistence`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/invoice/persistence/InvoiceOutboxPersistence.kt).

## Run the evidence

```bash
./gradlew :commerce-usage-billing-invoice-service:test --max-workers=1
./gradlew :commerce-usage-billing-invoice-service:integrationTest --max-workers=1
```

The default suite is container-free. PostgreSQL integration tests prove inbox
uniqueness, append-only line behavior, correction materialization, atomic local
outbox writes, and publisher fencing.

## Related services

- [`usage-billing-billing-service`](../usage-billing-billing-service/) publishes
  the charge and adjustment events consumed here.
- [`usage-billing-query-service`](../usage-billing-query-service/) projects
  invoice events for tenant and operator reads.
