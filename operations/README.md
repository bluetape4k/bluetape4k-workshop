# Job Operations Console

[한국어](README.ko.md) | English

This example builds one durable background-job contract and exposes it through
Spring MVC and Ktor. PostgreSQL is the only correctness authority. Redis and
SSE can reduce notification latency, but losing either one never changes job
state or invalidates the next REST snapshot.

## Modules

| Module | Responsibility | Runtime |
|---|---|---|
| [`job-console-core`](job-console-core/) | State machine, PostgreSQL queue, leases, checkpoints, retry budget, ETA, outbox, and shared fixtures | Java 25 |
| [`job-console-spring`](job-console-spring/) | Spring MVC REST/SSE adapter and demo UI | Java 25 |
| [`job-console-ktor`](job-console-ktor/) | Ktor Netty REST/SSE adapter and demo UI | Java 25 |

These modules use the repository-wide Java 25 default.

## Architecture

![Job operations console architecture](../docs/images/readme-diagrams/operations-job-console-readme-architecture-01.png)

Both adapters consume the same core contract. PostgreSQL owns submission,
idempotency, queue ordering, leases, state history, and outbox rows. Redis is an
advisory cancellation wake-up path only.

## Request sequence

![Job operations console request sequence](../docs/images/readme-diagrams/operations-job-console-readme-sequence-01.png)

The API commits a durable fact before returning or notifying. SSE events carry
only a stable event identifier and refresh hint; clients read the current REST
snapshot after every event.

## State machine

![Job operations console state machine](../docs/images/readme-diagrams/operations-job-console-readme-state-01.png)

A retryable failure returns the same job and queue identity to `queued` while
advancing its attempt and revision. `succeeded`, `failed`, `dead_lettered`, and
`cancelled` are terminal and reject later transitions.

## API contract

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/jobs` | Submit an idempotent deterministic job |
| `GET` | `/v1/jobs/{jobId}` | Read the authoritative redacted snapshot |
| `POST` | `/v1/jobs/{jobId}/cancel` | Commit a durable cancellation request |
| `GET` | `/v1/jobs/{jobId}/events` | Receive notification-only SSE events |
| `GET` | `/v1/queues/me` | Read a bounded caller queue page |
| `GET` | `/v1/tenants/{tenantId}/queue` | Read a bounded operator queue page |
| `GET` | `/healthz` | Process liveness |
| `GET` | `/readyz` | PostgreSQL-authoritative readiness with Redis degradation detail |

Submission requires `X-Demo-Tenant`, `X-Demo-Submitter`, and
`Idempotency-Key`. Operator queue access also requires
`X-Demo-Operator: true`. These trusted demo headers are intentionally simple
workshop fixtures. They are **not production authentication or authorization**.

The queue view is capped at 100 rows and uses an opaque cursor. ETA is a sampled
range with confidence and sample size, not an SLA; insufficient samples produce
no fabricated estimate.

## Run Spring MVC

Start PostgreSQL, then enable the demo profile explicitly:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/postgres
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
export SPRING_PROFILES_ACTIVE=demo
./gradlew :operations-job-console-spring:bootRun
```

Open `http://localhost:8080`. Without the `demo` profile, the demo UI and API
routes are not registered.

## Run Ktor

Start PostgreSQL, then opt in to the demo routes:

```bash
export POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/postgres
export POSTGRES_USERNAME=postgres
export POSTGRES_PASSWORD=postgres
export JOB_CONSOLE_DEMO=true
./gradlew :operations-job-console-ktor:run
```

Open `http://localhost:8080`. With `JOB_CONSOLE_DEMO=false`, the application
does not expose the demo routes.

## Failure fixtures

`failureMode` supports `none`, `retry_once`, `non_retryable`, and
`always_retryable`. The deterministic worker proves successful retry, immediate
terminal failure, retry exhaustion, lease recovery, cancellation races, Redis
loss, and outbox redelivery without a third-party provider.

## Operational boundaries

- PostgreSQL unavailable: readiness fails and no Redis-only mutation is accepted.
- Redis unavailable: readiness reports degradation, while PostgreSQL-backed work remains ready and correct.
- SSE disconnected or slow: the bounded fan-out evicts the subscriber; REST remains the source of truth.
- Worker crash: an expired lease returns eligible work to the durable queue.
- Rollback: stop either adapter, preserve PostgreSQL for inspection, and remove the three `operations-job-console-*` modules from deployment. No other workshop module depends on them.

## Verify

```bash
./gradlew :operations-job-console-core:test
./gradlew :operations-job-console-core:integrationTest --max-workers=1
./gradlew :operations-job-console-spring:integrationTest --max-workers=1
./gradlew :operations-job-console-ktor:integrationTest --max-workers=1
./scripts/smoke-validate.sh operations
```
