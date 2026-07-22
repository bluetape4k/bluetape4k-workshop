# Event-Sourced Usage Billing Microservices

[한국어](README.ko.md) | English

This advanced Spring Boot 4 / Java 25 reference separates usage billing into five independently deployable services: Meter, Usage, Billing, Invoice, and Query. It is for teams that already understand the transactional ledger and need to reason about the new failure boundaries created by separate PostgreSQL databases and Kafka at-least-once delivery.

Each service owns its database and its own integration decoder. JetBrains Exposed and `bluetape4k-exposed-jdbc` are the only database access path; every concrete repository implements `ExposedJdbcRepository`. There is no shared database, raw SQL/JDBC escape hatch, XA transaction, or end-to-end exactly-once claim.

![Service ownership and delivery boundaries](../../docs/images/readme-diagrams/usage-billing-microservices-architecture-01.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-architecture-01.svg)

## Choose this only when the boundary is real

Start with [`usage-metering-billing-ledger`](../usage-metering-billing-ledger/) when one PostgreSQL transaction is sufficient. Use this example when independent deployment, ownership, or scaling boundaries are real requirements and the team can operate replay, backlogs, redrive, and cross-service contract evolution.

| Concern | Modular monolith / ledger | This microservice reference |
| --- | --- | --- |
| Financial authority | One PostgreSQL authority | One local authority per service |
| Delivery | In-process transaction | Kafka at-least-once plus local outbox/inbox |
| Replay | Re-run one process | Re-deliver wire event; receiver absorbs it |
| Failure recovery | Transaction retry | Lease expiry, retry wait, quarantine, operator redrive |
| Operational cost | Lower | Higher: topic, lag, compatibility, five databases |

## Ownership and topics

| Service | Local authority | Publishes | Consumes |
| --- | --- | --- | --- |
| Meter | immutable price version | `PriceActivated` → `meter.events.v1` | — |
| Usage | idempotent accepted usage | `UsageAccepted` → `usage.events.v1` | price evidence |
| Billing | price evidence and immutable rated charge | `ChargeRated` → `billing.events.v1` | price and accepted usage |
| Invoice | immutable document line materialization | future invoice document events | rated charge / adjustment |
| Query | read model, checkpoint, quarantine/audit | — | all public topics |

Kafka is transport, not financial correctness authority. Every producer commits its local fact and a `PENDING` outbox row in one Exposed transaction. A relay claims that row with an owner-and-lease predicate, publishes, then marks it `PUBLISHED`. A crash after Kafka accepts a record but before the mark is deliberately recoverable as a duplicate delivery.

![Outbox and inbox state machines](../../docs/images/readme-diagrams/usage-billing-microservices-state-01.png)

[State diagram SVG source](../../docs/images/readme-diagrams/usage-billing-microservices-state-01.svg)

The receiver first validates its own JSON envelope contract, then durably records `(tenantId, eventId, payloadDigest)` before applying a local effect. Same ID/same digest is a duplicate; same ID/different digest is a correctness conflict and is quarantined. A retryable database failure is propagated to Kafka for redelivery. An unknown schema or digest mismatch becomes a durable Query quarantine entry so unrelated records can keep progressing.

## Run the evidence

JDK 25 and a Docker-compatible container runtime are required for PostgreSQL/Kafka integration paths.

```bash
./gradlew :commerce-usage-billing-meter-service:test --max-workers=1
./gradlew :commerce-usage-billing-usage-service:test --max-workers=1
./gradlew :commerce-usage-billing-billing-service:test --max-workers=1
./gradlew :commerce-usage-billing-invoice-service:test --max-workers=1
./gradlew :commerce-usage-billing-query-service:test --max-workers=1

./gradlew :commerce-usage-billing-meter-service:integrationTest \
  :commerce-usage-billing-usage-service:integrationTest \
  :commerce-usage-billing-billing-service:integrationTest \
  :commerce-usage-billing-invoice-service:integrationTest \
  :commerce-usage-billing-query-service:integrationTest \
  --max-workers=1

./gradlew :commerce-usage-billing-microservices-composition-tests:test \
  :commerce-usage-billing-microservices-composition-tests:integrationTest \
  --max-workers=1
```

Default tests are container-free and lock service-local decoder, envelope, idempotency, state, and repository contracts. Integration tests use Bluetape Testcontainers PostgreSQL fixtures to prove Exposed uniqueness, atomic local effect/outbox writes, replay, digest conflict, and fenced outbox completion.

## Production decision rules

- Price selection is Billing's local replicated evidence; Meter remains the authoritative publisher of price history.
- A `UsageAccepted` payload includes the accepted price provenance needed for Billing to rate from its own evidence rather than synchronously reading Usage or Meter tables.
- Invoice never edits a prior line. A correction is a new `AdjustmentPosted`-derived line referencing the original event.
- Query has no financial command endpoint. It owns read projection/checkpoint/quarantine visibility and operator redrive audit only.
- Offset commit follows a successful durable inbox/quarantine decision. It never follows a best-effort log line.

## Staged extraction and rollback

1. Keep the ledger/modular-monolith as source of truth; extract Meter and Usage first while dual-checking accepted-usage counts and price evidence.
2. Add Billing's replicated price evidence and rated-charge parity checks. Drain its outbox before routing downstream traffic.
3. Add Invoice document materialization and Query read models last. Compare immutable source-event IDs and totals, not mutable rows.
4. Roll back traffic routing only. Do not copy a service database backwards or rewrite published financial history; leave durable events, outboxes, inboxes, and quarantine audit records intact for reconciliation.

An operator should first inspect outbox backlog/state, oldest retry, inbox/quarantine reason, and the affected aggregate key. Redrive preserves the stored payload and creates audit evidence; it is not an edit surface for amounts or prices.

## Module map

| Module | Purpose |
| --- | --- |
| `usage-billing-meter-service` | price authority and outbox |
| `usage-billing-usage-service` | usage receipt, price evidence inbox, accepted usage outbox |
| `usage-billing-billing-service` | pricing evidence inbox, rating, charge outbox |
| `usage-billing-invoice-service` | charge inbox and immutable invoice lines |
| `usage-billing-query-service` | projection inbox, checkpoint, quarantine, operator diagnostics |
| `usage-billing-microservices-composition-tests` | contract/composition test-only boundary |
