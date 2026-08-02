# Usage Billing Billing Service

[한국어](README.ko.md) | English

`usage-billing-billing-service` owns replicated price evidence and immutable
charge rating. It consumes Meter and Usage events, calculates the charge in its
own PostgreSQL transaction, and publishes `ChargeRated` on `billing.events.v1`.

![Usage billing service boundaries](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.svg)

## Responsibility

- consume `PriceActivated` and `UsageAccepted` from their public topics;
- retain Meter price evidence locally rather than reading Meter or Usage tables;
- apply inbox deduplication, digest conflict quarantine, and aggregate-version
  ordering;
- calculate `unitPrice × quantity` and append an immutable `BillingCharge`;
- publish `ChargeRated`, `AdjustmentPosted`, or `BillingPeriodClosed` through a
  local outbox.

This module has no HTTP controller. `BillingInboxService` and
`BillingAdjustmentService` are application boundaries used by the tests and
composition fixture.

## Inbound event contract

`BillingInboundEventDecoder` accepts schema `1` for `PriceActivated` and
`UsageAccepted`, and verifies the envelope payload digest before the inbox
journal sees it.

| Inbox outcome | Meaning |
| --- | --- |
| `APPLIED` | event is new, evidence is available, and the expected aggregate version is present |
| `DUPLICATE` | the same event ID and payload digest was already applied, or the event is an old version |
| `DEFERRED` | local price evidence or a prior aggregate version is not available yet |
| `QUARANTINED` | the event ID conflicts with another digest or the contract is permanently invalid |

An applied `UsageAccepted` is rated from Billing's local evidence. A future
aggregate-version gap is deferred for redelivery; it is never silently skipped.

## Charge and correction boundary

The local transaction appends the inbox decision, immutable charge, and outbox
row together. `BillingAdjustmentService` creates a new negative adjustment
fact; it does not rewrite the original charge. Outbox rows use
`PENDING → CLAIMED → PUBLISHED`, with `RETRY_WAIT` and `QUARANTINED` recovery
states and a fenced lease-based completion update.

Relevant source: [`BillingInboxService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/billing/application/BillingInboxService.kt),
[`BillingPricingEvidenceService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/billing/application/BillingPricingEvidenceService.kt),
[`BillingInboundEventDecoder`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/billing/messaging/BillingKafkaConsumer.kt),
and [`BillingOutboxPersistence`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/billing/persistence/BillingOutboxPersistence.kt).

## Run the evidence

```bash
./gradlew :commerce-usage-billing-billing-service:test --max-workers=1
./gradlew :commerce-usage-billing-billing-service:integrationTest --max-workers=1
```

The default suite is container-free. Integration tests use PostgreSQL to prove
price-evidence uniqueness, inbox ordering, atomic charge/outbox writes, digest
conflicts, and fenced publisher completion.

## Related services

- [`usage-billing-meter-service`](../usage-billing-meter-service/) publishes the
  price history consumed as local evidence.
- [`usage-billing-usage-service`](../usage-billing-usage-service/) publishes the
  accepted usage that is rated here.
- [`usage-billing-invoice-service`](../usage-billing-invoice-service/) consumes
  rated charges and adjustments.
