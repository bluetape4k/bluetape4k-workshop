# Concert Ticket Flash Sale

[한국어](README.ko.md)

This Spring Boot 4 / Kotlin example models the failure-prone core of a concert flash sale: waiting-room admission, per-user and per-IP purchase guards, PostgreSQL inventory serialization, unknown payment outcomes, late approval, refund, ticket issue/revoke, and operator recovery.

The design is a Spring Modulith modular monolith. It uses JetBrains Exposed through Bluetape4k's exact `ExposedJdbcRepository` contract for normal database work. Direct JDBC is restricted to the versioned startup migration runner. Redis uses `bluetape4k-lettuce` for temporary coordination; it is never the durable source of sold inventory or payment truth.

![Spring Modulith architecture](../../docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-architecture-01.png)

## Prerequisites and Java 25

- JDK 25. This new example intentionally differs from the repository-wide Java 21 default.
- Docker-compatible container runtime for the PostgreSQL and Redis integration tests.
- PostgreSQL 18 and Redis 8 for a manual boot run; the tests provision compatible containers through `bluetape4k-testcontainers`.
- No individual Bluetape library BOM. The workshop consumes only `bluetape4k-dependencies`.

Verify the toolchain and the example:

```bash
java -version
./gradlew :commerce-concert-ticket-flash-sale:test --max-workers=1
./gradlew :commerce-concert-ticket-flash-sale:build --max-workers=1
```

The opt-in hostile concurrency evidence requires a unique run ID:

```bash
./gradlew :commerce-concert-ticket-flash-sale:ticketStressTest \
  -PticketStressRun=local-ticket-check --max-workers=1
```

The stress thresholds are regression evidence for the local environment, not production capacity promises.

## Run, seed, and reset

Start local dependencies:

```bash
docker run --name ticket-postgres --rm -d \
  -e POSTGRES_USER=ticket -e POSTGRES_PASSWORD=ticket -e POSTGRES_DB=ticket \
  -p 5432:5432 postgres:18
docker run --name ticket-redis --rm -d -p 6379:6379 redis:8
```

Run the loopback-only demo profile:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/ticket
export SPRING_DATASOURCE_USERNAME=ticket
export SPRING_DATASOURCE_PASSWORD=ticket
export WORKSHOP_TICKET_DEMO_OPERATOR_TOKEN=replace-with-a-long-local-secret
./gradlew :commerce-concert-ticket-flash-sale:bootRun \
  --args='--spring.profiles.active=demo --server.address=127.0.0.1'
```

Open `http://127.0.0.1:8080/`. The page is a recovery-contract explorer. The core module deliberately does not expose public sale creation, seed, reset, purchase-start, or network SSE endpoints. Integration tests create deterministic fixtures directly at the module boundary. This keeps a destructive reset API out of the production-shaped application.

For a clean manual database, stop the application and recreate only the named local container:

```bash
docker stop ticket-postgres ticket-redis
```

Starting the two commands above again creates empty stores. The application migration is versioned, checksummed, and protected by a PostgreSQL advisory transaction lock.

## Join the waiting room

The demonstrated admission flow has two distinct authorities:

1. Redis grants a short-lived, single-use waiting-room token and atomically owns the USER/IP lease.
2. PostgreSQL rechecks the sale window, inventory, USER/IP guard, and policy version inside the purchase transaction.

The Redis grant reduces a thundering herd; it does not reserve inventory. If Redis is unavailable, new purchase admission fails closed while liveness and PostgreSQL-backed recovery remain available. The production adapter should derive the user subject from an authenticated principal and the IP subject from a trusted-proxy allowlist, never from an arbitrary forwarded header.

## Purchase and replay a lost response

`PurchaseService.start` consumes the admission grant, takes the inventory and guard locks in a fixed order, and commits the attempt plus stable payment operation ID in one Exposed transaction. The relevant repositories implement the exact Bluetape4k `ExposedJdbcRepository` interface.

![Normal purchase sequence](../../docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-normal-purchase-sequence-01.png)

If an HTTP response is lost, retry with the same idempotency key and identical fingerprint. A different request body with the same key is a conflict. Once the caller knows the attempt ID, recovery is owner scoped:

```bash
BUYER=11111111-1111-1111-1111-111111111111
ATTEMPT=replace-with-seeded-attempt-id
curl -i \
  -H "X-Demo-Buyer: ${BUYER}" \
  "http://127.0.0.1:8080/api/v1/purchase-attempts/${ATTEMPT}"
```

The response has `Cache-Control: no-store`. An absent attempt and another buyer's attempt both return the same not-found problem, preventing identifier probing.

## Reconcile timeout and late approval

A provider timeout is `UNKNOWN`, not `DECLINED`. The payment worker persists a fenced claim, calls the provider with the stable operation ID, and on restart looks up that same ID before making another effect. Stale claim results cannot overwrite a newer revision.

![Timeout and late approval sequence](../../docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-timeout-refund-sequence-01.png)

If cancellation was requested while the outcome was unknown and lookup later reports approval, the attempt becomes `REFUND_PENDING` with ticket disposition `NEVER_ISSUED`. Ticket issue is suppressed. This is the key late-approval invariant: compensate the money without creating a usable ticket.

Run a bounded operator pass only after inspecting health and backlog evidence:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/operator/reconciliation-runs \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Token: replace-with-a-long-local-secret' \
  -d '{"limit":25,"reason":"reconcile unknown payment operations"}'
```

The reference application supplies the bounded operator framework but no default `ReconciliationJob` beans. A production adapter must wire payment lookup, refund lookup, and ticket-effect recovery jobs explicitly; an empty result must not be read as proof that no backlog exists.

## Cancel, refund, revoke, and restock

Cancellation requests convergence; it does not pretend the provider immediately cancelled. Use the same owner identity:

```bash
curl -i -X POST \
  -H "X-Demo-Buyer: ${BUYER}" \
  "http://127.0.0.1:8080/api/v1/purchase-attempts/${ATTEMPT}/cancellation"
```

Inventory may be returned only when both sides are conclusively safe:

- authorization declined or an unapproved hold expired; or
- refund succeeded and the ticket was never issued; or
- refund succeeded and an issued ticket was successfully revoked.

`REFUND_PENDING`, `REVOKE_PENDING`, and either quarantine state are not restock signals. Manual SQL updates are forbidden because they bypass the same invariant enforced by the Exposed transaction.

![Integrated state model](../../docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-integrated-state-01.png)

## Operator invariant and backlog checks

Check signals in this order:

| Signal | Healthy reading | Warning | Action | Recovery check |
|---|---|---|---|---|
| Migration readiness | `UP` | checksum/lock failure | stop rollout, compare immutable migration | readiness returns `UP` after a clean restart |
| Redis readiness | `UP` | `redis_unavailable` | reject new admission; keep recovery online | lease acquire/validate/release probe passes |
| Payment unknown age | bounded by provider SLA | oldest age grows | run bounded lookup reconciliation | stable IDs reach conclusive outcomes |
| Refund backlog | stable or draining | `REFUND_PENDING` grows | verify provider lookup then retry | refund receipts converge without duplicates |
| Ticket quarantine | zero | any quarantined effect | inspect provider receipt and repair adapter | disposition becomes `REVOKED` or known safe |
| DB bulkhead | low rejection ratio | sustained foreground rejection | reduce ingress before raising pool size | transaction p99 and permit use normalize |

Do not run unbounded reconciliation. The operator API limits each request to 50 items, requires a reason, has an independent permit, and stops at the configured deadline. Metrics use low-cardinality result codes; buyer, IP, attempt, and provider IDs belong only in access-controlled diagnostics.

## State mapping: internal state to customer action

| Internal state | Customer message | Allowed action |
|---|---|---|
| `INVENTORY_HELD` | Inventory is temporarily held | wait; do not start another purchase |
| `PAYMENT_AUTHORIZING` | Payment is processing | retry snapshot with the same attempt ID |
| `RECONCILIATION_REQUIRED` | Payment result is being confirmed | wait or request cancellation; never repay |
| `CANCELLATION_REQUESTED` | Cancellation is converging | wait for final provider lookup |
| `APPROVED` | Purchase approved | use ticket only after ticket state is `ISSUED` |
| `DECLINED`, `CANCELLED`, `EXPIRED` | Purchase did not complete | a new admission may be attempted |
| `REFUND_PENDING` | Refund is processing | keep the attempt ID; contact support only after SLA |
| `REFUNDED` | Refund complete | no further action |
| `REFUND_QUARANTINED` | Manual review required | operator investigation; no automatic restock |

The browser always renders a text label and an icon with an accessible name. Color is supplementary, never the only state signal.

## Redis and PostgreSQL authority

![Redis and PostgreSQL authority](../../docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-authority-01.png)

| Concern | Redis | PostgreSQL |
|---|---|---|
| Waiting-room lease | primary temporary coordinator | optional durable audit/reference |
| USER/IP duplicate suppression | fast atomic lease | durable unique guard and recovery authority |
| Inventory | never authoritative | locked counters and invariant owner |
| Payment/refund | never authoritative | stable operation, claim, outcome, receipt |
| Ticket issue/revoke | never authoritative | effect claim, receipt, disposition |
| HTTP idempotency | optional edge optimization | fingerprint and replay authority |

The reusable two-key Lua lease is intentionally local pending promotion to `bluetape4k-lettuce`; follow-up is tracked in [bluetape4k-projects #1065](https://github.com/bluetape4k/bluetape4k-projects/issues/1065).

## Production security boundary

The `demo` profile accepts `X-Demo-Buyer` and `X-Operator-Token` only from a loopback peer. Never expose that profile through a proxy or container port bound to an untrusted network.

Production intentionally has no header authenticator and fails closed. Supply a real Spring Security adapter that:

- validates JWT/OAuth2 with the deployment IdP;
- maps a stable immutable subject UUID;
- applies operator RBAC independently from customer authentication;
- configures trusted proxies before deriving client IP;
- keeps CSRF disabled only for a stateless bearer-token API;
- redacts provider payloads, credentials, buyer/IP subjects, and operation IDs from public problems and metrics.

The static page is public documentation; every `/api/**` endpoint still requires authentication, and operator endpoints additionally require `ROLE_OPERATOR`.

## Microservice extraction guide

![Microservice extraction guide](../../docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-microservice-extraction-01.png)

Extract only after the modular boundaries and recovery behavior are measured. A safe order is:

1. Keep purchase and inventory together first; they own the strongest transaction invariant.
2. Extract ticketing behind stable effect IDs and consumer receipts.
3. Extract payment only when the PG supports lookup/idempotency by stable operation ID.
4. Extract admission last or independently because Redis loss must not affect durable recovery.

Each service owns its tables. Replace in-process Modulith publication with a transactional outbox and schema-versioned events. Preserve owner-scoped recovery queries, deadlines, fenced claims, dedup receipts, and low-cardinality observability. Do not introduce distributed transactions or a shared database.

The in-module `TicketEventStream` proves a bounded snapshot-first subscription contract and slow-consumer eviction. It is not a durable event log or a network `SseEmitter` endpoint. A production HTTP adapter must reconnect from a durable high-water mark or fall back to owner-safe polling; the demo page shows that polling behavior without claiming durable SSE replay.

### Verification

```bash
node scripts/validate-ticket-flash-sale-runbook.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-readme-diagram-qa.mjs
node scripts/validate-sequence-diagrams.mjs
./gradlew :commerce-concert-ticket-flash-sale:test --max-workers=1
```
