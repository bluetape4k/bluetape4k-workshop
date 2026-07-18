# Issue #524 Planning Contracts Design

- Date: 2026-07-18
- Repository: `bluetape4k/bluetape4k-workshop`
- Branch: `feature/issue-524-planning-contracts`
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/524
- Module: `optimization/planning-contracts`
- Gradle project: `:optimization-planning-contracts`

## Problem

Optimization reference applications need a reusable application-owned boundary
for submitting versioned planning datasets, receiving asynchronous provider
results, and turning an accepted result into a domain command. The boundary must
be useful before any shared optimization library or live Timefold Platform
tenant exists.

The workshop currently has no `optimization/` module group and no example that
demonstrates all of the following together:

- provider-neutral `PlanningEngine` contracts;
- PostgreSQL-backed request, inbox, outbox, and audit state;
- duplicate, stale, and out-of-order callback handling;
- aggregate-version revalidation before a final command;
- deterministic offline provider fixtures;
- Java 25 virtual-thread execution for blocking HTTP and JDBC boundaries.

## Current Evidence

- Issue #524 explicitly requires a deterministic fake and contract fixtures;
  live provider credentials and public webhook deployment are optional evidence.
- The root build imports only `bluetape4k-dependencies:1.3.1` for Bluetape
  version governance. Published Exposed modules must therefore remain
  versionless aliases resolved by that BOM.
- The catalog already exposes `bluetape4k-exposed-core`,
  `bluetape4k-exposed-jdbc`, `bluetape4k-exposed-jdbc-tests`,
  `bluetape4k-exposed-jackson3`, `bluetape4k-http`,
  `bluetape4k-jackson3`, `bluetape4k-idgenerators`,
  `bluetape4k-micrometer`, `bluetape4k-virtualthread-api`, and
  `bluetape4k-virtualthread-jdk25`.
- `exposed/mvc-jdbc` demonstrates the released interface-based repository API:
  `override val table`, `extractId`, and `ResultRow.toEntity`.
- `messaging/kafka-outbox-fallback` demonstrates bounded PostgreSQL lease,
  retry, and dead-letter state that can be borrowed without adding Kafka.
- `bluetape4k-http` provides a production virtual-thread HTTP client, but its
  default transient retry must not make provider submission `POST` ambiguous.
- The repository's CodeGraph index returned no matching nodes for the searched
  repository/outbox/virtual-thread symbols, so the design is grounded in direct
  source inspection plus GNO and live issue evidence.

## Goals

1. Add a Spring Boot 4.1 MVC reference module under `optimization/`.
2. Target Java 25 for every module below `optimization/` while keeping other
   workshop modules on the Java 21 target.
3. Use `bluetape4k-virtualthread-api` with the JDK 25 runtime provider for
   blocking HTTP, servlet, and background-worker execution.
4. Use PostgreSQL, HikariCP, Exposed JDBC, and Bluetape Exposed repositories as
   the persistence baseline.
5. Make the deterministic fake the default provider and keep default CI fully
   network-free.
6. Persist request and outbox state atomically, then converge duplicate and
   restart delivery to one accepted plan audit history.
7. Reject stale or out-of-order provider results and revalidate the owning
   aggregate version before issuing a final command.
8. Expose a redacted read model containing score, revision, status, and safe
   constraint explanations only.

## Non-goals

- Do not publish a shared production optimization library.
- Do not require a Timefold tenant, API key, or public webhook route.
- Do not make a plan result a final inventory, booking, staffing, or dispatch
  decision without the application-owned aggregate-version check.
- Do not add Redis, Kafka, JaVers, or Resilience4j in the first implementation.
- Do not use `bluetape4k-exposed-timefold-solver-persistence`; it persists
  local Solver score types and does not define this provider-neutral HTTP
  contract.
- Do not use direct JDK executor factories when Bluetape's virtual-thread SPI
  provides the lifecycle boundary.

## Approach Options

### Option A: Application-owned PostgreSQL contracts with deterministic fake

Create one Spring MVC module with a provider-neutral port, PostgreSQL request,
inbox, outbox, and audit tables, a deterministic fake adapter, and disabled HTTP
adapter profiles backed by recorded fixtures.

Benefits:

- Delivers the issue without unpublished libraries or external credentials.
- Produces the concrete contract evidence later examples can reuse.
- Makes consistency and replay behavior inspectable in one workshop module.
- Maximizes relevant Bluetape ecosystem reuse without adding irrelevant
  infrastructure.

Costs:

- The workshop owns a small amount of application-specific lease and callback
  SQL.
- A live provider deployment remains optional follow-up evidence.

### Option B: Wait for shared HTTP idempotency and PostgreSQL concurrency modules

Delay #524 until #1055 and #391 publish common infrastructure.

Benefits:

- Potentially less application-owned fixture code.

Costs:

- Reverses the intended evidence flow: examples should prove the shared
  boundary before extraction.
- Treats unpublished capability as a delivery blocker contrary to #524.

### Option C: Redis/Kafka-first asynchronous pipeline

Use Redis or Kafka for callback dedupe and worker dispatch, with PostgreSQL only
for final request state.

Benefits:

- Demonstrates distributed queue infrastructure.

Costs:

- Adds two consistency systems before the provider contract is stable.
- Makes aggregate-version revalidation and restart proof harder to read.
- Redis is unnecessary for the first PostgreSQL-owned idempotency contract.

## Decision

Use Option A. #1055 and #391 remain parallel contract/fixture tracks, not
sequential deployment dependencies. Redis may be added later only if a second
consumer proves a cache, coordination, or distributed inbox need; that path must
use Lettuce.

## Architecture

### Runtime components

- `PlanningContractsApplication`: Spring Boot application entrypoint.
- `PlanningEngine`: provider-neutral submission/status port.
- `DeterministicPlanningEngine`: default, network-free adapter.
- `TimefoldPlatformPlanningEngine`: disabled profile adapter for the Timefold
  Platform REST contract.
- `CustomSolverPlanningEngine`: disabled profile adapter for a custom Solver
  service using the same versioned HTTP contract.
- `PlanningRequestService`: creates a request and outbox row in one transaction.
- `PlanningOutboxWorker`: claims due work, invokes the selected adapter on a
  virtual thread, and records retry/dead-letter state in a task-owned
  transaction.
- `PlanningCallbackService`: verifies callback authenticity, inserts the inbox
  event if absent, compares provider revision and aggregate version, and appends
  one accepted audit record.
- `PlanningCommandService`: revalidates the owning aggregate version in
  PostgreSQL immediately before returning a final command candidate.
- `PlanningQueryService`: maps persistence state to a redacted read model.
- `PlanningController`: request, callback, processing, command, and query
  workshop endpoints.

### Persistence model

`planning_aggregates`

- `aggregate_id` unique business id
- `aggregate_version`
- `updated_at`

`planning_requests`

- UUID v7 `id`
- `aggregate_id`, `aggregate_version`, `dataset_id`
- `parent_revision`, `accepted_revision`
- `status`, `score_summary`, `redacted_explanation`
- `provider`, `provider_request_id`
- audit timestamps

`planning_outbox`

- Long id
- `planning_request_id` unique
- `payload` as a closed JSON document
- `status`, `attempt_count`, `next_attempt_at`
- `claimed_by`, `claimed_until`
- bounded sanitized `last_error_code`, `last_error_summary`
- audit timestamps

`planning_callback_inbox`

- Long id
- `provider`, `event_id` unique together
- `planning_request_id`, `provider_revision`
- `received_at`, `processed_at`, `outcome`
- audit timestamps

`planning_audits`

- Long id
- `planning_request_id`, `callback_event_id`
- `aggregate_version`, `provider_revision`
- `status`, `score_summary`, `redacted_explanation`
- `decision`: accepted, duplicate, stale revision, aggregate changed, rejected
- `created_at`

Repositories actively use the Bluetape interfaces:

- `PlanningRequestRepository : UUIDAuditableJdbcRepository`
- `PlanningOutboxRepository : LongAuditableJdbcRepository`
- `PlanningCallbackInboxRepository : LongAuditableJdbcRepository`
- `PlanningAuditRepository : LongJdbcRepository`

Inherited CRUD, existence, count, and paging behavior is used directly.
Application-specific SQL is limited to insert-if-absent, lease claim, revision
compare/update, and aggregate-version revalidation.

### Data flow

1. `POST /api/planning/requests` validates a versioned dataset reference.
2. One Spring transaction inserts the request and its outbox row.
3. The worker claims a bounded batch and submits through `PlanningEngine` on a
   Bluetape Java 25 virtual-thread executor.
4. The deterministic fake returns a recorded asynchronous result; HTTP adapters
   expose the same normalized contract behind non-default profiles.
5. `POST /api/planning/callbacks/{provider}` verifies the signature before any
   state change, then performs inbox insert-if-absent.
6. Duplicate event ids are no-ops. New events compare provider revision and the
   request's owning aggregate version before appending an audit entry.
7. Only an accepted, newest revision updates the request read state.
8. `POST /api/planning/requests/{id}/commands` re-reads the aggregate version in
   PostgreSQL. A changed version rejects the command candidate.
9. `GET /api/planning/requests/{id}` returns only normalized, redacted fields.

## Virtual-thread contract

- `optimization/*` compiles and tests with a Java 25 toolchain.
- Non-optimization modules keep Java/Kotlin target 21.
- The module depends on `bluetape4k-virtualthread-api` and uses
  `runtimeOnly(bluetape4k-virtualthread-jdk25)`.
- The transitive JDK 21 provider is excluded from every module configuration so
  runtime provider selection cannot be ambiguous.
- Tomcat and worker executors are created through `VirtualThreads` and owned by
  Spring lifecycle beans.
- Incoming Spring transactions stay on the Tomcat virtual thread. JDBC work is
  not submitted to another executor from inside the transaction.
- Each outbox task opens its own transaction inside the virtual-thread task.
- Monitor-based synchronization is prohibited in the new code.

## HTTP adapter contract

- `productionVirtualThreadHttpClientOf` supplies the Java 25 blocking client.
- GET/status calls may use the client's bounded transient retry.
- Provider submission `POST` must disable automatic transport retry unless that
  provider proves an idempotency-key contract. PostgreSQL outbox replay remains
  the authoritative retry layer.
- Request/response fixtures are bounded JSON documents with explicit content
  type, timeout, body-close, and redaction behavior.
- WireMock contract tests cover submit success, timeout, 5xx, malformed JSON,
  status lookup, and sensitive-field redaction without external network access.

## Callback security

- The deterministic fake bypasses signature verification only through its
  explicit test/profile verifier.
- HTTP provider profiles use JCE `Mac` with configured raw webhook secrets and
  constant-time signature comparison.
- Missing, malformed, expired, or mismatched signatures fail before inbox
  insertion.
- Raw callback payloads, secrets, provider credentials, stack traces, and JDBC
  URLs never appear in read models or stored error summaries.
- Request and callback bodies have explicit size limits.

## Ecosystem capability selection

| Responsibility | Reused Bluetape module/capability | Decision and reason | Unavailable/fake constraint |
|---|---|---|---|
| Version governance | `bluetape4k-dependencies:1.3.1` | Sole Bluetape version authority; no individual BOM or module pin | None |
| JDBC repositories | `bluetape4k-exposed-core`, `bluetape4k-exposed-jdbc` | Use UUID/Long repository interfaces and auditable tables | Custom atomic SQL remains application-owned |
| Repository tests | `bluetape4k-exposed-jdbc-tests` | Reuse PostgreSQL repository test fixtures/utilities where compatible | Production dependency is still `exposed-jdbc` |
| JSON mapping | `bluetape4k-exposed-jackson3`, `bluetape4k-jackson3` | Closed DTO serialization without default typing | Provider fixtures are module-owned |
| Blocking HTTP | `bluetape4k-http` | Production virtual-thread HC5 client | Submit retry is disabled/controlled by adapter |
| Virtual threads | `bluetape4k-virtualthread-api`, `bluetape4k-virtualthread-jdk25` | JDK-neutral API with Java 25 runtime provider | JDK 21 provider excluded |
| Identifiers | `bluetape4k-idgenerators` | UUID v7 request ids | Event ids remain provider-supplied and unique per provider |
| Logging context | `bluetape4k-logging` | `KLogging` and request/provider MDC context | Raw payload logging prohibited |
| Observability | `bluetape4k-micrometer` | Observation around submit/callback/command boundaries | No external telemetry backend required |
| PostgreSQL tests | `bluetape4k-testcontainers` | `PostgreSQLServer.Launcher.postgres` singleton | Docker required only for integration lane |
| HTTP fixtures | Bluetape WireMock launcher | Local recorded provider fixtures | No live tenant/API key in default CI |
| Concurrency tests | `bluetape4k-junit5`, `MultithreadingTester` | Prove duplicate callback convergence and lease behavior | No ad-hoc raw thread harness |
| Redis | None | Not needed for application-owned PostgreSQL inbox/outbox | If later required, use Lettuce |
| Kafka | None | No broker boundary in #524 | Revisit only after a consumer contract requires it |
| Leader election | None | Single-process workshop worker with DB leases is sufficient | Multi-instance scheduling can reuse leader later |
| Audit snapshots | None | Explicit append-only planning audit is clearer than JaVers diffs | JaVers rejected for this contract |
| Timefold persistence | None | Local Solver score persistence is not provider-neutral HTTP | Live adapters remain disabled profiles |

## Failure modes and recovery

| Failure | Required behavior | Evidence |
|---|---|---|
| Crash after request commit before submit | Due outbox row is reclaimed after restart | PostgreSQL restart/replay integration test |
| HTTP timeout or 5xx on submit | Release/expire lease, schedule bounded retry, then dead-letter | WireMock plus repository integration test |
| Crash after provider accepts but before local acknowledgement | Replay uses the same request id and provider idempotency key when supported; otherwise status reconciliation precedes another POST | Adapter contract test |
| Duplicate callback | Unique `(provider,event_id)` makes processing a no-op and produces no second accepted audit | Concurrent callback test |
| Out-of-order callback | Older provider revision is audited as stale and cannot replace accepted state | Integration test |
| Aggregate changed during solve | Callback is recorded but not accepted; final command also rejects changed aggregate version | Integration test |
| Invalid signature | Reject before inbox insertion with no sensitive output | MVC negative test |
| Malformed provider body | Sanitize failure, keep outbox retryable/dead-letterable, do not persist raw exception | WireMock negative test |
| Worker shutdown with active tasks | Stop claiming, close executor after bounded drain, leave leases recoverable | Lifecycle test |

## Read model and API boundaries

`PlanningReadModel` exposes only:

- `requestId`
- `datasetId`
- `aggregateId`
- `aggregateVersion`
- `revision`
- `status`
- `score`
- `constraintExplanations` as bounded redacted summaries
- timestamps

It never exposes submission payload, callback body, webhook signature, API key,
provider error body, or stored outbox payload.

## Compatibility and migration

- This is a new application module and does not change an existing public
  library API.
- The root build gains path-sensitive toolchains: Java 25 for `optimization/*`,
  Java 21 elsewhere.
- CI runs on a JDK capable of resolving both toolchains; compiled target levels
  remain module-specific.
- No database migration tool is introduced. The workshop initializes isolated
  schemas through Exposed; production extraction would require Flyway or an
  equivalent migration system.

## Test strategy

1. Pure contract tests for fake determinism, normalized status, redaction, and
   command-version comparison.
2. PostgreSQL repository tests through
   `PostgreSQLServer.Launcher.postgres` for unique inbox insertion, lease claim,
   revision ordering, and aggregate revalidation.
3. `MultithreadingTester` stress for duplicate callbacks and outbox claim
   exclusivity.
4. WireMock adapter tests for submit/status error and lifecycle contracts.
5. Spring MVC integration tests for request, callback, read model, and command
   endpoints.
6. Restart proof that a committed due outbox row converges to one accepted audit
   after worker reconstruction.

Testcontainers commands run sequentially with `--max-workers=1` and use fresh
test execution when stale output could hide a failure.

## Acceptance criteria mapping

| Issue criterion | Design proof |
|---|---|
| Final command revalidates aggregate version in PostgreSQL | `PlanningCommandService` and changed-version integration test |
| Duplicate callback and restart retry converge to one audit history | unique inbox key, atomic callback service, restart/replay integration test |
| Read models expose score/revision/status/redacted explanation only | closed `PlanningReadModel`, JSON leak assertions |
| Deterministic fake works without live provider | default fake profile and recorded fixtures |
| Timefold Platform and custom Solver boundaries are separate | two disabled HTTP adapter profiles behind one port |
| Java 25 and virtual threads are the JVM default | path-sensitive toolchain and runtime provider assertion |
| Bluetape ecosystem is actively reused | capability table and dependency/repository tests |

## Review convergence

The approved chat design was reviewed again against the six required lenses.

| Lens | Initial finding | Repair in this spec | Latest blocker count |
|---|---|---|---|
| Performance | Unbounded outbox polling and callback explanation size could grow | Bounded claim batches, payload/body/explanation limits | P0=0, P1=0 |
| Stability | HC5 retry plus outbox retry could duplicate submit POST | Submit retry explicitly disabled/controlled; status reconciliation precedes ambiguous replay | P0=0, P1=0 |
| Security | Callback secret verification and raw error leakage were underspecified | JCE HMAC, constant-time compare, pre-inbox rejection, closed redacted DTOs | P0=0, P1=0 |
| Operator/Ops | Restart and executor shutdown ownership were unclear | Recoverable DB leases, bounded drain, task-owned transaction, metrics | P0=0, P1=0 |
| Developer/API | Repository reuse could be reduced to name-only adoption | Exact Bluetape repository interfaces and limited custom SQL are specified | P0=0, P1=0 |
| User/caller | A caller could mistake an accepted plan for a final business decision | Separate final-command endpoint with mandatory aggregate revalidation | P0=0, P1=0 |

No P0/P1 finding remains. P2: live Timefold webhook deployment is deferred
because credentials and a public route are optional external evidence, not the
offline contract's completion condition.

## Definition of Done

- `:optimization-planning-contracts` appears in `./gradlew projects`.
- Optimization modules compile and test with Java 25 and the JDK 25 Bluetape
  virtual-thread provider.
- The module uses the approved Spring Boot, Exposed, PostgreSQL, and Bluetape
  dependency stack without an individual library BOM or explicit Bluetape pin.
- Deterministic fake, HTTP fixture adapters, repository pattern, inbox/outbox,
  callback, audit, and final-command behaviors are covered.
- Targeted module tests, Testcontainers tests, detekt, workflow syntax,
  registration checks, bilingual README parity, and `git diff --check` pass.
- CI/Nightly and validation groups include the Java 25 container-backed module.
- No Redis/Kafka dependency or live network requirement enters default CI.
