# Order Lifecycle Fulfillment

[한국어](README.ko.md) | English

This Spring Boot MVC example keeps order, payment, inventory reservation,
fulfillment, cancellation, and refund lifecycles independent. PostgreSQL is authoritative;
Spring Modulith persists application-event publications, and a deterministic
payment provider makes success, failure, duplicate, and out-of-order scenarios
repeatable without an external payment dependency.

## What This Teaches

- Model each lifecycle with its own status and revision instead of one global order state.
- Use `bluetape4k-exposed-jdbc` repositories for PostgreSQL aggregate persistence.
- Use `bluetape4k-exposed-jdbc-tests` and `PostgreSQLServer` for production-shaped tests.
- Make HTTP submission idempotent with an application-owned PostgreSQL table.
- Keep failed event publications visible and replay them through a bounded operator endpoint.
- Deliver a snapshot first, then incremental audit events over SSE.
- Use Java 25 virtual threads while keeping JDBC concurrency bounded by HikariCP.

## Lifecycle Boundaries

| Aggregate | Example progression |
|-----------|---------------------|
| Order | `SUBMITTED -> ACCEPTED -> FULFILLMENT_IN_PROGRESS` |
| Payment attempt | `CREATED -> AUTHORIZING -> SUCCEEDED|FAILED` |
| Inventory reservation | `HELD -> COMMITTED|RELEASED|RECONCILIATION_REQUIRED` |
| Fulfillment group | `REQUESTED -> ALLOCATED -> PICKING -> SHIPPED -> DELIVERED` |
| Cancellation case | `REQUESTED -> APPROVED|REJECTED` |
| Refund case | `REQUESTED -> PENDING_PROVIDER -> SUCCEEDED|FAILED|MANUAL_REVIEW` |

Payment success does not complete the order. Split fulfillment groups can
progress independently, and cancelling an unshipped line creates independent
cancellation and refund cases without rolling back a line that has already
shipped.

## Example Scenarios

| Scenario | How to reproduce | Evidence to inspect |
|----------|------------------|---------------------|
| Idempotent submission | Send the same key and canonical payload twice, then reuse the key with a changed SKU. | The second response has `Idempotency-Replayed: true`; the changed payload receives HTTP 409. Logs contain only the key-hash prefix, never the raw key or payload. |
| Delayed and conflicting provider events | Create a `DELAYED_SUCCESS` order, deliver the delayed success, then submit the same provider event ID with a different payload in the integration fixture. | Payment reaches `SUCCEEDED` once. Duplicate and out-of-order events do not reapply the terminal state; the conflicting payload remains counted as unresolved PostgreSQL evidence. |
| Split shipment and partial cancellation | Create the default order. Its `sku-beta` quantity of two is distributed across `GROUP-1` and `GROUP-2`. Advance `GROUP-1` to `SHIPPED`, then cancel one `sku-beta` unit. | The shipped link remains quantity one, only the unshipped `GROUP-2` link becomes zero, and cancellation and refund cases retain separate revisions and audit rows. |
| Failed publication replay | Arm the deterministic inventory-listener failure in the integration fixture, deliver payment success, then call the bounded replay endpoint. | The failed publication stays visible until replay; inventory and fulfillment are created exactly once. |

For a browser-only walkthrough, create the default `SUCCESS` order, advance
`GROUP-1` through `ALLOCATED`, `PICKING`, and `SHIPPED`, then use **Cancel one**
on `sku-beta`. The console refreshes from SSE and shows the shipped group,
cancelled group, approved cancellation, succeeded refund, aggregate revisions,
and audit history independently.

## Architecture

![Order lifecycle fulfillment architecture](../../docs/images/readme-diagrams/commerce-order-lifecycle-fulfillment-readme-architecture-01.png)

The HTTP boundary runs on Java 25 virtual threads, but PostgreSQL concurrency
remains bounded by HikariCP. Application-owned idempotency, Exposed
repositories, Spring Modulith publications, provider inbox evidence, audit
history, query/SSE services, `bluetape4k-logging`, and Micrometer remain
separate operational boundaries.

## Sequence Diagram

![Split fulfillment and partial cancellation sequence](../../docs/images/readme-diagrams/commerce-order-lifecycle-fulfillment-readme-sequence-01.png)

The sequence uses one real split line: one `sku-beta` unit is already shipped
with `GROUP-1`, while only the remaining unit in `GROUP-2` is cancelled. A
delivered remainder plus cancelled groups completes the order; an all-cancelled
set transitions the order to `CANCELLED`.

## REST API And Browser

Open `http://localhost:8080/` after starting the application. The browser console
submits deterministic orders, reads the current snapshot, subscribes to SSE,
shows aggregate revisions and audit history, advances fulfillment groups,
cancels active line quantities, delivers delayed deterministic payment success,
and exposes bounded publication replay for the workshop operator flow.

Submit an order:

```bash
curl -i -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-0001' \
  -d '{
    "tenantId":"tenant-demo",
    "customerReference":"customer-order-0001",
    "providerMode":"SUCCESS",
    "lines":[
      {"sku":"sku-a","quantity":1,"unitPrice":10.00},
      {"sku":"sku-b","quantity":2,"unitPrice":20.00}
    ]
  }'
```

Reusing the key with the same canonical payload returns the stored response and
`Idempotency-Replayed: true`. Reusing it with a different payload returns HTTP
409 with `IDEMPOTENCY_FINGERPRINT_CONFLICT`. Only a fixed-length key hash is
stored.

Operator replay is deliberately bounded:

```bash
curl -X POST \
  -H 'X-Workshop-Operator: local-console' \
  'http://localhost:8080/api/v1/operations/publications/replay-failed?batchSize=10'
```

`batchSize` cannot exceed 100. A production deployment must protect this route
with operator authentication and authorization. The local-only custom header is
a CSRF guard for the browser workshop, not an authentication credential.

## Concurrency And Timeouts

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 8
      minimum-idle: 2
      connection-timeout: 60000
  transaction:
    default-timeout: 60s
order-lifecycle:
  idempotency:
    cleanup-batch-size: 250
    cleanup-interval: 1h
  sse:
    max-connections: 1000
    max-concurrent-polls: 4
    poll-interval: 1s
server:
  address: 127.0.0.1
  tomcat:
    threads:
      max: 8000
    max-connections: 8000
    accept-count: 1000
```

With Spring virtual threads enabled, Tomcat ignores `threads.max`; the value is
kept as a platform-thread fallback. `max-connections` is the effective HTTP
admission limit. The Hikari pool stays small because virtual threads do not make
PostgreSQL connections cheaper. The longer connection and transaction timeouts
allow bounded waiting; they are not a substitute for database capacity or
backpressure. SSE clients for the same order share one poller, and at most four
poll queries can enter PostgreSQL concurrently. Terminal idempotency responses
expire after 24 hours and a scheduled cleanup deletes at most 250 completed or
failed rows per run; active leases are never removed by cleanup. The workshop
binds to loopback because it deliberately has no identity provider. Before
setting `ORDER_SERVER_ADDRESS` to a non-loopback address, protect tenant and
operator routes with authentication and authorization.

## Operational Logging

Operational components use `bluetape4k-logging` with lazy `KLogging` messages.
Command outcomes, idempotency dispositions, provider-event decisions,
aggregate revisions, publication replay, refund completion, and SSE open/release
events use stable `key=value` fields. Raw idempotency keys, canonical payloads,
response bodies, and customer data are intentionally excluded.

## Run And Verify

The application expects PostgreSQL values from `application.yml` or the
`ORDER_DATABASE_*` environment variables. Tests start PostgreSQL through
`PostgreSQLServer`.

HTTP integration tests use `RANDOM_PORT + WebTestClient.bindToServer()` so the
real Tomcat, virtual-thread, static-resource, and SSE boundaries are exercised.

```bash
./gradlew :commerce-order-lifecycle-fulfillment:test --max-workers=1
./gradlew :commerce-order-lifecycle-fulfillment:bootJar
```

The module uses the repository-wide `bluetape4k-dependencies` BOM. Bluetape
module versions are intentionally not pinned locally.
