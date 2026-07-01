# DDD Order Audit

[한국어](README.ko.md) | English

This workshop shows how to keep a DDD aggregate, Spring Modulith event publication, a PostgreSQL-backed transaction, an after-commit fulfillment handler, and JaVers audit history in one small example.

The important rule is simple: the order row and the Modulith publication row are committed together in PostgreSQL. Fulfillment and JaVers history are side effects that run only after that commit.

![DDD order audit architecture](../../docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-architecture-01.png)

SVG source: [spring-modulith-ddd-order-audit-readme-architecture-01.svg](../../docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-architecture-01.svg)

## What You Learn

| Topic | Code to read | What to check |
|---|---|---|
| Aggregate lifecycle | `orders/OrderDomain.kt` | `Order.place()` and `Order.approve()` return new aggregate values and safe domain events. |
| Transactional publication | `orders/OrderCommandService.kt` | The order save and `eventPublisher.publishEvent(...)` run inside one command transaction. |
| PostgreSQL integration test | `AbstractDddOrderAuditTest.kt` | Tests use `PostgreSQLServer.Launcher.postgres` from `bluetape4k-testcontainers`, not H2. |
| After-commit fulfillment | `fulfillment/FulfillmentReservationHandler.kt` | `@ApplicationModuleListener` reserves fulfillment only after the order transaction commits. |
| Failed publication replay | `FulfillmentPublicationTest.kt` | A failed listener leaves a failed publication row and replay is idempotent. |
| After-commit audit | `orders/OrderAuditService.kt` | JaVers snapshots are registered with `TransactionSynchronization.afterCommit()`. |

## Sequence

The sequence diagram separates the command transaction from after-commit work. Labels are numbered in execution order so you can follow where rollback safety comes from.

![DDD order audit sequence](../../docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-sequence-01.png)

SVG source: [spring-modulith-ddd-order-audit-readme-sequence-01.svg](../../docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-sequence-01.svg)

## Run the Example Tests

Run the module tests serially because they share a PostgreSQL Testcontainer and Spring Modulith publication tables:

```bash
./gradlew :spring-modulith-ddd-order-audit:test --console=plain --max-workers=1
```

The test suite verifies:

- placing an order persists the order and line rows in PostgreSQL;
- approving an order creates a Modulith publication row in the same command transaction;
- rollback leaves no order row, publication row, fulfillment reservation, or JaVers snapshot;
- a failed fulfillment listener can be replayed without duplicate reservations;
- JaVers exposes a compact audit trail and a status diff after approval.

## Design Notes

`Order` is the aggregate root. It validates command input, rejects repeated approval, and records events as value data. `OrderCommandService` persists the aggregate with JPA and publishes the aggregate events while the transaction is still active.

Spring Modulith stores event publication state in PostgreSQL. That gives the example a transactional-outbox-style safety boundary without introducing a broker. `FulfillmentReservationHandler` runs after commit and uses the order id as its reservation id, so replaying the same `OrderApproved` event is idempotent.

`OrderAuditService` keeps the JaVers boundary explicit. The example intentionally commits audit snapshots after the transaction commits; a rollback does not leak audit history for an order that never committed.

## Safety Rule

Treat domain events and audit entries as durable data. Do not put passwords, tokens, payment secrets, or raw PII in event payloads, publication rows, exception messages, or JaVers properties. Use stable ids and reader-safe business fields instead.
