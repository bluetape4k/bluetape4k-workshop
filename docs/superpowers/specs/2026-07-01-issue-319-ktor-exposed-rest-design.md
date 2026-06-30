# Issue #319 - Ktor Exposed REST Workshop Spec

- Date: 2026-07-01
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/319
- Work type: Type A Full Feature
- Target repository: `bluetape4k/bluetape4k-workshop`
- Target module: `ktor/exposed-rest`
- Gradle project: `:ktor-exposed-rest`

## Problem

`bluetape4k-dependencies 1.3.1` includes the published
`bluetape4k-exposed-ktor` integration, but `bluetape4k-workshop` does not yet
show how a Ktor application should use Exposed transactions through that helper
boundary. Existing examples cover Ktor coroutine basics and several Exposed
Spring variants, but learners need a small non-Spring REST service that shows:

- Ktor route handlers calling Exposed JDBC transactions safely.
- `StatusPages` mapping from `bluetape4k-exposed-ktor`.
- PostgreSQL-backed integration tests using the shared
  `bluetape4k-testcontainers` launcher instead of an embedded database.
- README and diagrams that make the request, transaction, and database
  boundaries obvious.

## Current Evidence

- Issue #319 is open in milestone `1.3.1`, assigned to `debop`, with labels
  `documentation`, `enhancement`, `difficulty:advanced`, `area:data-access`,
  and `area:async-reactive`.
- `settings.gradle.kts` auto-registers `ktor/*` directories as `:ktor-*`
  modules, so `ktor/exposed-rest` becomes `:ktor-exposed-rest`.
- `gradle/libs.versions.toml` already imports the root
  `bluetape4k-dependencies` BOM, but does not yet expose aliases for
  `bluetape4k-ktor-core`, `bluetape4k-ktor-testing`, or
  `bluetape4k-exposed-ktor`.
- `bluetape4k-exposed-ktor` exposes:
  - `installBluetape4kExposedKtor(...)`
  - `StatusPagesConfig.bluetape4kExposedErrors()`
  - `ApplicationCall.exposedJdbcTransaction(...)`
- `bluetape4k-exposed-ktor` maps transaction failures to safe Ktor error
  responses and rethrows `CancellationException`.
- `bluetape4k-testcontainers` exposes
  `io.bluetape4k.testcontainers.database.PostgreSQLServer.Launcher.postgres`
  with reusable `jdbcUrl`, `username`, `password`, and `driverClassName`
  accessors.
- Existing workshop modules use `PostgreSQLServer.Launcher.postgres` for
  PostgreSQL-backed integration tests, and run container-backed examples in
  serial CI lanes.

## Constraints

- Use the root `bluetape4k-dependencies` BOM only. Do not pin a bluetape4k
  module version.
- Use PostgreSQL, not H2. Tests must use
  `PostgreSQLServer.Launcher.postgres` from `bluetape4k-testcontainers`.
- Do not instantiate raw `GenericContainer`.
- Keep Testcontainers-backed verification serial with `--max-workers=1`.
- Default runtime code must not start a Testcontainer. Test code supplies
  PostgreSQL connection properties.
- Keep the example focused on Ktor + Exposed JDBC. Do not replace Spring
  examples or cover every Exposed backend.
- README work is bilingual: `README.md` and `README.ko.md`.
- Diagram work must use `$bluetape4k-diagram`, include SVG and PNG assets, pass
  the current checklist, and record full-size visual inspection evidence.
- New module registration must cover root/Ktor README tables, example CI
  coverage, stale-check project counts, and `./gradlew projects`.

## Goals

1. Add `ktor/exposed-rest` as a Ktor REST application backed by Exposed JDBC.
2. Use `bluetape4k-exposed-ktor` for route-level transaction execution and
   exposed error mapping.
3. Use PostgreSQL Testcontainers in integration tests through
   `PostgreSQLServer.Launcher.postgres`.
4. Demonstrate CRUD, rollback, sanitized database error mapping, readiness, and
   cancellation propagation where applicable.
5. Keep code small enough for learners to read end to end.
6. Document route examples, dependency notes, and focused Gradle test commands
   in both README locales.
7. Add architecture and sequence diagrams that follow the current
   best-practices style.
8. Register the module in CI/container validation without making normal smoke
   tests require Docker.

## Non-Goals

- Do not use H2, R2DBC, Spring Boot, or JPA in this module.
- Do not introduce a repository abstraction layer beyond what the example
  needs to teach route-to-transaction boundaries.
- Do not make Testcontainers part of the production main application.
- Do not add live cloud, Redis, Kafka, or external service dependencies.
- Do not restore hard Kover thresholds or unrelated CI policy.

## Approach Options

### Option A - Ktor + Exposed JDBC + PostgreSQLServer

Create a Ktor module with caller-owned Hikari/Exposed resources. Tests create
those resources from `PostgreSQLServer.Launcher.postgres` and exercise real
PostgreSQL through Ktor's test host.

Benefits:

- Matches the issue scope and user requirement exactly.
- Uses the same Testcontainers launcher pattern as neighboring modules.
- Teaches realistic PostgreSQL behavior while keeping the application small.
- Keeps CI Docker usage isolated to the container-backed lane.

Costs:

- Docker is required for the focused module test command.
- Test setup must reset schema state carefully because the PostgreSQL launcher
  is shared.

### Option B - H2-backed Ktor Exposed Example

Copy the simpler H2 pattern from another workshop and keep default tests
Docker-free.

Benefits:

- Faster tests and less CI setup.

Costs:

- Rejected by the explicit requirement to use PostgreSQL.
- Teaches weaker behavior for transaction and SQL failure paths.

### Option C - Ktor + Exposed R2DBC

Build a reactive Ktor example with Exposed R2DBC and PostgreSQL.

Benefits:

- Useful async data-access extension.

Costs:

- Broader than issue #319.
- Adds driver/pool and reactive transaction concepts before learners have seen
  the basic Ktor/JDBC integration.

## Decision

Use Option A. The module will be a focused Ktor REST service with Exposed JDBC
transactions, PostgreSQL Testcontainers integration tests, bilingual README
files, and two README diagrams.

## Architecture

### Runtime Components

- `KtorExposedRestApplication`: Ktor entrypoint and environment-property
  bridge for manual local runs.
- `KtorExposedRestResources`: owns `HikariDataSource`, Exposed `Database`, and
  the blocking JDBC coroutine dispatcher.
- `BookRoutes`: defines CRUD and failure-demonstration routes.
- `BookRepository`: table setup and Exposed statements for the learner-facing
  `Book` resource.
- `BookRequest`, `BookResponse`, and `ErrorResponse`: serializable DTOs.
- `bluetape4k-exposed-ktor`: supplies `exposedJdbcTransaction`,
  `installBluetape4kExposedKtor`, and safe `StatusPages` mappings.
- PostgreSQL Testcontainer: supplied only by tests through
  `PostgreSQLServer.Launcher.postgres`.

### Data Flow

1. A learner sends a Ktor HTTP request.
2. The route validates request payload or path parameters.
3. The route calls `ApplicationCall.exposedJdbcTransaction(...)`.
4. Exposed executes SQL against PostgreSQL through the caller-owned Hikari pool.
5. The route maps rows to learner-friendly response DTOs.
6. `StatusPages` handles Exposed transaction failures and direct SQL failures
   without leaking JDBC URLs or credentials.
7. Cancellation failures are not converted into database errors.

### Failure Handling

- Validation failures use Kotlin/bluetape4k `require*` checks and Ktor
  `StatusPages`.
- Missing books return a small 404 JSON response.
- The rollback route inserts inside a transaction and then fails, proving that
  the inserted row is not committed.
- A direct SQL failure route proves sanitized database error mapping.
- A cancellation route/test proves cancellation is propagated instead of being
  wrapped as a database response.

## Test Strategy

- TDD red tests precede production implementation.
- Use `testApplication` with Ktor's test host.
- Start PostgreSQL only through
  `PostgreSQLServer.Launcher.postgres`.
- Reset the Exposed schema at resource creation so repeated runs are
  deterministic.
- Cover:
  - create/list/read/update/delete book routes,
  - rollback after an inserted row,
  - sanitized `SQLException` mapping,
  - Exposed readiness route,
  - cancellation propagation where Ktor test host exposes it.
- Run focused tests with:
  `./gradlew :ktor-exposed-rest:test --warning-mode all --console=plain --max-workers=1`.

## Documentation and Diagram Strategy

- `README.md` explains the English learning path, dependencies, route examples,
  PostgreSQL Testcontainers requirement, and focused Gradle commands.
- `README.ko.md` mirrors the English README with natural Korean technical
  prose.
- Architecture diagram uses top-to-bottom flow with clear layer bands:
  Client, Ktor API, Exposed Transaction Boundary, PostgreSQL.
- Sequence diagram follows the current best-practices palette and checklist:
  numbered labels above call lines, transparent branch bodies, activation bars,
  muted branch colors, rounded elbow connectors where applicable, and
  color-matched arrowheads.
- Diagram evidence must include `diagram-qa`, SVG validation, rendered PNG
  generation, full-size visual inspection, and concrete checklist counts.

## Validation

- `./gradlew :ktor-exposed-rest:test --warning-mode all --console=plain --max-workers=1`
- `./gradlew :ktor-exposed-rest:compileKotlin --warning-mode all --console=plain`
- `./gradlew projects --console=plain`
- `./scripts/smoke-validate.sh stale-check`
- `./scripts/smoke-validate.sh data-access-full`
- `./scripts/smoke-validate.sh diagram-qa`
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## Risks

- PostgreSQL Testcontainers can be slower than H2; keep it out of the default
  smoke lane and run it serially.
- Ktor test host cancellation behavior can surface as a propagated exception
  rather than an HTTP response; the test should assert the propagation path
  instead of forcing an artificial response.
- Diagram regressions are likely if labels cover lines or colors drift from
  the best-practices sequence style; run the full checklist before PR.
