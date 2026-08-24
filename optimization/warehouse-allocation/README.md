# Warehouse Allocation

`optimization-warehouse-allocation` is a Java 25/Spring Boot reference
application for deterministic warehouse allocation and pick-wave proposals.
It uses synthetic orders and stock snapshots. PostgreSQL is the final
authority for approval-time reservations; the planner only returns a proposal.

## Scope

- Models order lines, warehouse/SKU stock, pick waves, carrier cutoffs, shipping
  rules, picker capacity, and committed allocation pins.
- Applies stock, capability, cutoff, capacity, incident, and pin constraints in
  a deterministic planner with bounded input/output.
- Persists plan, reservation, inbox, idempotency, audit, replan, outbox, and
  local-effect records with revisions and compare-and-set updates.
- Provides a redacted query model and a local `FAKE` planning/callback seam.

Production WMS, robotics, carrier APIs, automatic stock commits, Kafka brokers,
and Timefold credentials are outside this example. Recorded outbox events are
fixture evidence, not proof of production fulfillment.

## Run locally

The default application binds HTTP and management endpoints to loopback and
uses the `test` profile. Mutation routes are additionally protected by the
local-only `demo` profile and `X-Demo-Operator: true` header.

```bash
./gradlew :optimization-warehouse-allocation:bootRun \
  --args='--spring.profiles.active=test,demo'

curl -s http://127.0.0.1:8080/warehouse-allocation
curl -s http://127.0.0.1:8080/actuator/health
curl -s http://127.0.0.1:8080/api/warehouse-allocation/stock
```

Use a fresh bounded `Idempotency-Key` and `X-Request-Id` for every demo
mutation. The demo header is a local guard, not authentication, authorization,
CSRF protection, or an acceptable production credential.

```bash
curl -s -X POST http://127.0.0.1:8080/api/warehouse-allocation/events \
  -H 'Content-Type: application/json' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: event-001' \
  -H 'X-Request-Id: request-001' \
  -d '{"eventId":"event-001","eventKey":"inventory-001","eventType":"INVENTORY_ADJUSTED","sourceEventRevision":1,"target":{"warehouseId":"wh-001","sku":"sku-001"},"payload":{"onHandQuantity":10}}'

curl -s -X POST http://127.0.0.1:8080/api/warehouse-allocation/replans \
  -H 'Content-Type: application/json' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: replan-001' \
  -H 'X-Request-Id: request-002' \
  -d '{"datasetId":"demo-dataset","parentPlanRevision":0}'

curl -s 'http://127.0.0.1:8080/api/warehouse-allocation/outbox/<operation-key>'
```

The fixture ABI is available only from the `testFixtures` source set:
`WarehouseAllocationFixturePort.reset(seed)`, `ingest(canonicalEvent)`, and
`snapshot(datasetId)`. It is intended for deterministic tests, not for
production data loading.

## Verification

The module uses PostgreSQL Testcontainers for persistence tests. A healthy
Docker/Colima context is required; an unavailable container runtime is a
`PENDING` verification result, not a passing test.

```bash
./gradlew :optimization-warehouse-allocation:cleanTest \
  :optimization-warehouse-allocation:test \
  --no-build-cache --max-workers=1 --console=plain
./scripts/smoke-validate.sh optimization
```

Diagnostics and performance artifacts, when produced, belong under
`build/reports/warehouse-allocation-diagnostics/` and
`build/reports/performance/*.jfr`. Logs and payloads must remain redacted.

The module uses only the root `bluetape4k-dependencies` BOM for Bluetape
versions and does not depend on the internal implementation of
`planning-contracts`.
