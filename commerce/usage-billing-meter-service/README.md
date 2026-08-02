# Usage Billing Meter Service

[한국어](README.ko.md) | English

`usage-billing-meter-service` is the price authority in the usage-billing
microservice reference. It records immutable price versions and publishes the
`PriceActivated` integration event without sharing a database with Usage,
Billing, Invoice, or Query.

![Usage billing service boundaries](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.svg)

## Responsibility

- accept `ActivatePriceCommand` for a tenant, meter, currency, unit price, and
  effective time;
- treat `(tenantId, idempotencyKey)` as the command identity;
- persist an immutable `MeterPriceVersion` and a durable command receipt;
- publish `PriceActivated` on `meter.events.v1` through a local outbox.

This module has no HTTP controller. `MeterCommandService` is the application
boundary used by the contract, persistence, and composition tests.

## Activation contract

`MeterCommandService.activatePrice` validates non-blank identifiers and a
positive `unitPrice`. It hashes the command fields with SHA-256 before writing:

| Outcome | Behavior |
| --- | --- |
| first command | create price version `1`, envelope schema `1`, and a `PENDING` outbox row |
| same idempotency key and same fingerprint | replay the existing result without a second price version |
| same idempotency key and different fingerprint | reject with `MeterIdempotencyConflict` |

The envelope contains `meterCode`, `currency`, and `unitPrice`, plus an
independent payload digest. The local transaction commits the command receipt,
price version, envelope, and outbox row together.

## Delivery and recovery

`MeterOutboxPublisher` claims rows with an owner-and-lease predicate, publishes
to `meter.events.v1`, and fences `markPublished` with the same ownership token.
The durable state machine is:

`PENDING → CLAIMED → PUBLISHED`

Transport failures move a claimed row to `RETRY_WAIT`; exhausted or invalid
records can be `QUARANTINED`. A crash after Kafka accepts a record but before
the local status update is intentionally recovered as duplicate delivery by
the receiving service.

Relevant source: [`MeterCommandService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/meter/application/MeterCommandService.kt),
[`MeterIntegrationEnvelope`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/meter/integration/MeterIntegrationEnvelope.kt),
and [`MeterOutboxPersistence`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/meter/persistence/MeterOutboxPersistence.kt).

## Run the evidence

The default suite is container-free. PostgreSQL and Kafka integration tests use
Testcontainers and must run serially with the repository's `TestMutexService`.

```bash
./gradlew :commerce-usage-billing-meter-service:test --max-workers=1
./gradlew :commerce-usage-billing-meter-service:integrationTest --max-workers=1
```

## Related services

- [`usage-billing-usage-service`](../usage-billing-usage-service/) consumes the
  price evidence and accepts usage.
- [`usage-billing-microservices-composition-tests`](../usage-billing-microservices-composition-tests/)
  exercises delayed publication, replay, and recovery across all five services.
