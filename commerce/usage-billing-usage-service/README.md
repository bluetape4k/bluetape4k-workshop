# Usage Billing Usage Service

[한국어](README.ko.md) | English

`usage-billing-usage-service` accepts usage facts after the Meter price evidence
has arrived locally. It makes the source event identity and accepted price
provenance durable before publishing `UsageAccepted` on `usage.events.v1`.

![Usage billing service boundaries](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.svg)

## Responsibility

- consume `PriceActivated` from `meter.events.v1` into local price evidence;
- deduplicate usage by `(tenantId, sourceSystem, sourceEventId)`;
- accept only positive quantities with a matching tenant, meter, and currency
  price evidence;
- write the usage fact and its `PENDING` outbox row in one PostgreSQL
  transaction.

There is no HTTP controller. `UsageCommandService` is the application boundary;
Kafka listener and outbox publisher are separate delivery boundaries.

## Accept usage

`AcceptUsageCommand` requires non-blank tenant, source, event, meter, and
currency identifiers plus a positive quantity. The service records the unit
price from local `PriceEvidence`; it does not synchronously read Meter's
database.

| Outcome | Behavior |
| --- | --- |
| price evidence exists and the source event is new | persist `UsageRecord`, envelope schema `1`, and outbox row |
| same source identity and same fingerprint | return the existing acceptance as a replay |
| same source identity and different fingerprint | reject with `UsageSourceConflict` |
| no local price evidence | reject with `MissingPriceEvidence` |

The published envelope contains the accepted price provenance needed by Billing
to rate from its own replicated evidence.

## Price evidence inbox

The Meter listener validates event type `PriceActivated`, schema `1`, required
fields, and the payload digest before storing local evidence. Its outcomes are
`APPLIED`, `DUPLICATE`, and `QUARANTINED`. A duplicate delivery is safe; a digest
conflict or invalid contract is not silently applied.

The usage outbox follows `PENDING → CLAIMED → PUBLISHED`, with `RETRY_WAIT` for
transport failures and `QUARANTINED` for exhausted or invalid records. The
publisher waits up to five seconds for the Kafka transport and fences the final
status update with its lease.

Relevant source: [`UsageCommandService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/usage/application/UsageCommandService.kt),
[`PriceEvidenceService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/usage/application/PriceEvidenceService.kt),
[`UsageIntegrationEnvelope`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/usage/integration/UsageIntegrationEnvelope.kt),
and [`UsageOutboxPersistence`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/usage/persistence/UsageOutboxPersistence.kt).

## Run the evidence

```bash
./gradlew :commerce-usage-billing-usage-service:test --max-workers=1
./gradlew :commerce-usage-billing-usage-service:integrationTest --max-workers=1
```

The default suite proves command, decoder, envelope, idempotency, publisher,
and repository contracts without containers. The integration suite proves
PostgreSQL uniqueness, local evidence, atomic usage/outbox writes, and replay.

## Related services

- [`usage-billing-meter-service`](../usage-billing-meter-service/) publishes the
  price evidence this service requires.
- [`usage-billing-billing-service`](../usage-billing-billing-service/) consumes
  `UsageAccepted` and rates the charge locally.
