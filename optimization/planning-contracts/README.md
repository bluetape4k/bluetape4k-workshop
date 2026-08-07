# Planning Contracts

[한국어](README.ko.md) | English

This Spring Boot reference application demonstrates an application-owned
boundary for asynchronous optimization providers. A planning result is a
proposal, not a final business decision: the application re-reads the owning
aggregate version from PostgreSQL before returning a command candidate.

## Contract

1. `POST /api/planning/requests` stores the request and one outbox row in the
   same transaction.
2. `PlanningOutboxWorker` claims due rows with a bounded PostgreSQL lease and
   invokes `PlanningEngine` on a Bluetape Java 25 virtual thread.
3. The default deterministic fake needs no network or credentials. Disabled
   `timefold` and `custom-solver` profiles map separate HTTP endpoints to the
   same normalized contract.
4. Callback signatures are verified before any state change. A unique
   `(provider, event_id)` inbox key makes repeated delivery a no-op.
5. Requests must target the active engine, and callbacks must match the
   provider stored on the request. Mismatches append an audit decision without
   changing accepted state.
6. New callbacks append an immutable audit decision. Stale revisions and
   changed aggregate versions remain visible without replacing accepted state.
7. The query API excludes outbox payloads, callback signatures, credentials,
   raw provider bodies, and internal errors.

## Bluetape stack

| Responsibility | Capability |
|---|---|
| Version authority | `bluetape4k-dependencies:1.4.0` |
| Repositories | `UUIDAuditableJdbcRepository`, `LongAuditableJdbcRepository`, `LongJdbcRepository` from `bluetape4k-exposed-jdbc` |
| PostgreSQL tests | `PostgreSQLServer.Launcher.postgres` and released `bluetape4k-exposed-jdbc-tests` test support |
| Virtual threads | `bluetape4k-virtualthread-api` plus runtime `bluetape4k-virtualthread-jdk25` |
| Provider HTTP | `productionVirtualThreadHttpClientOf`; submit `POST` disables automatic retry |
| Concurrency tests | `MultithreadingTester` |

The module declares JetBrains Exposed coordinates without versions. The
current `bluetape4k-dependencies:1.4.0` resolution selects JetBrains Exposed
`1.4.0` and Bluetape Exposed `1.12.1`; those library versions are not the BOM
version itself.

The module excludes the JDK 21 virtual-thread provider from every
configuration. Redis is not needed for this first PostgreSQL-owned contract; if
a later example proves that need, it must use Lettuce.

## Persistence

| Table | Role |
|---|---|
| `planning_aggregates` | Current aggregate version used by callback and command checks |
| `planning_requests` | Normalized request state and redacted result projection |
| `planning_outbox` | Lease, retry, completion, and dead-letter state |
| `planning_callback_inbox` | Provider event idempotency |
| `planning_audits` | Append-only callback decisions |

## API walkthrough

```bash
curl -s -X POST http://localhost:8080/api/planning/requests \
  -H 'Content-Type: application/json' \
  -d '{"aggregateId":"roster-42","aggregateVersion":7,"datasetId":"dataset-42","provider":"FAKE"}'

curl -s -X POST http://localhost:8080/api/planning/process

curl -s -X POST http://localhost:8080/api/planning/callbacks/fake \
  -H 'Content-Type: application/json' \
  -H 'X-Planning-Signature: fake' \
  -d '{"eventId":"event-42","planningRequestId":"<request-id>","providerRevision":2,"status":"SUCCEEDED","scoreSummary":"0hard/-2soft","constraintExplanations":["balanced workload"]}'

curl -s http://localhost:8080/api/planning/requests/<request-id>
curl -s -X POST http://localhost:8080/api/planning/requests/<request-id>/commands
```

The processing endpoint is deliberately synchronous for workshop inspection.
A production application should schedule the worker behind authenticated
operator controls instead of exposing it as a public endpoint.
Callback bodies are capped at 256 KiB before parsing, and provider responses
are streamed only up to 64 KiB.

## Provider profiles

The fake provider is active by default. HTTP profiles require both a base URL
and a webhook secret:

```bash
SPRING_PROFILES_ACTIVE=timefold \
PLANNING_PROVIDER_BASE_URL=https://example.invalid \
PLANNING_CALLBACK_SECRET=replace-me \
./gradlew :optimization-planning-contracts:bootRun
```

Use `custom-solver` instead of `timefold` for the custom Solver endpoint map.
The workshop tests use WireMock and never contact either external service.

## Verify

Java 25 and Docker are required.

```bash
./gradlew :optimization-planning-contracts:cleanTest \
  :optimization-planning-contracts:test \
  --no-build-cache --max-workers=1
```

The suite proves Java 25 runtime selection, JDK 25 virtual threads, repository
CRUD, concurrent inbox/outbox convergence, transaction rollback, callback
security, HTTP no-retry semantics, redacted MVC responses, and final aggregate
version checks.
