# Issue #322 - DDD Order Audit Workshop Design

## Context

`bluetape4k-workshop` milestone `1.3.1` includes issue
[#322](https://github.com/bluetape4k/bluetape4k-workshop/issues/322), which
asks for a learner-facing example that combines:

- DDD aggregate command methods.
- Spring Modulith domain event publication.
- JaVers aggregate history and diff queries.
- A clear explanation of when to use domain events, Modulith events, outbox,
  and JaVers audit together.

Current repository evidence shows the ingredients already exist as separate
examples:

- `spring-modulith/events-deep-dive` teaches Spring application events,
  transactional listeners, and module boundaries.
- `spring-modulith/jpa-demo` teaches Spring Modulith module encapsulation with
  JPA.
- `exposed/javers-persistence-audit` teaches JaVers audit history with a Redis
  persistence store and Exposed current-row persistence.

The missing learning boundary is a single service flow where an aggregate
command changes state, audit history is committed, and a Spring Modulith listener
reacts through the publication registry.

## Current Evidence

- Live issue #322 is open, assigned to `debop`, and attached to milestone
  `1.3.1`.
- `settings.gradle.kts` auto-registers child modules under `spring-modulith/`,
  so `spring-modulith/ddd-order-audit` becomes
  `:spring-modulith-ddd-order-audit`.
- `./gradlew :spring-modulith-events-deep-dive:test --console=plain --no-daemon`
  passed with 10 tests before design work.
- Official Spring Modulith documentation describes
  `@ApplicationModuleListener` as a transactional asynchronous listener tied to
  the event publication registry.
- Official JaVers documentation confirms the workshop can use `commit`,
  `findSnapshots`, `findChanges`, and `compare` for audit and diff behavior.
- `bluetape4k-javers` already provides reusable DDD helpers:
  `AggregateRoot`, `DomainEvent`, `AggregateRepository`, and
  `SpringApplicationEventDomainEventPublisher`.

## Approved Direction

Create a new `spring-modulith/ddd-order-audit` module.

The user approved the recommended direction with one change: use PostgreSQL
instead of H2. Therefore the default test path will use
`bluetape4k-testcontainers` PostgreSQL infrastructure and must run in the
container-backed lane, not the H2 smoke lane.

## Architecture

The module will model an order approval workflow:

1. `OrderCommandService` receives commands such as `PlaceOrder` and
   `ApproveOrder`.
2. `Order` aggregate enforces invariants through explicit command methods.
3. `OrderAuditRepository` persists the aggregate and commits the saved state to
   JaVers.
4. `SpringApplicationEventDomainEventPublisher` publishes domain events after
   the surrounding transaction commits.
5. `FulfillmentReservationHandler` handles `OrderApproved` with
   `@ApplicationModuleListener`.
6. Query code reads JaVers snapshots, changes, and diffs for the aggregate.

The module will use PostgreSQL Testcontainers as the source-of-truth database
for application state and Spring Modulith publication rows. JaVers will use the
default in-memory repository unless implementation discovery proves the
PostgreSQL-backed JaVers path is already available through the current BOM and
does not add unnecessary setup. This keeps the lesson focused on the event and
audit boundary while still proving the transactional service path against a real
PostgreSQL database.

## Design Alternatives

### Option A - PostgreSQL + in-memory JaVers + Modulith publication

This is the selected approach.

Pros:

- Proves the service transaction and publication rows against PostgreSQL.
- Keeps the audit API easy to inspect with JaVers snapshots and diffs.
- Reuses `javers-ddd` helpers instead of inventing a local DDD framework.
- Fits a single workshop module and a single learner journey.

Cons:

- JaVers snapshot storage itself is not PostgreSQL-backed by default.
- Container-backed tests are heavier and must be registered in the full lane.

### Option B - PostgreSQL + PostgreSQL-backed JaVers repository

Pros:

- Gives one physical database for application rows, publication rows, and audit
  snapshots.

Cons:

- Requires more repository setup and risks turning the lesson into JaVers
  persistence plumbing.
- Overlaps with existing persistence-specific audit examples.
- Should only be chosen if the current BOM exposes a simple, stable JaVers
  SQL/Exposed repository path that does not distract from the DDD/Modulith
  lesson.

### Option C - Redis-backed JaVers, like `exposed/javers-persistence-audit`

Pros:

- Demonstrates durable JaVers history with an existing repository pattern.

Cons:

- Duplicates the Redis audit lesson.
- Requires more Testcontainers and makes the module about cross-store audit
  failure boundaries instead of DDD + Modulith integration.

## Module Shape

Expected files:

- `spring-modulith/ddd-order-audit/build.gradle.kts`
- `spring-modulith/ddd-order-audit/README.md`
- `spring-modulith/ddd-order-audit/README.ko.md`
- `spring-modulith/ddd-order-audit/src/main/kotlin/...`
- `spring-modulith/ddd-order-audit/src/test/kotlin/...`
- `spring-modulith/ddd-order-audit/src/test/resources/junit-platform.properties`
- `spring-modulith/ddd-order-audit/src/test/resources/logback-test.xml`
- README diagram SVG/PNG assets under `docs/images/readme-diagrams/`

Expected package prefix:

```text
io.bluetape4k.workshop.spring.modulith.ddd.audit
```

## Domain Model

The domain should stay small:

- `OrderId`, `CustomerId`, and `Money` value objects.
- `OrderStatus`: `PLACED`, `APPROVED`, `CANCELLED`.
- `OrderLine` and `Order` aggregate.
- Commands: `PlaceOrderCommand`, `ApproveOrderCommand`.
- Events: `OrderPlaced`, `OrderApproved`.

Aggregate rules:

- An order must have at least one line.
- Quantity and unit price must be positive.
- Only `PLACED` orders can be approved.
- Cancelled orders cannot be approved.
- Commands must return new aggregate instances rather than mutating state.

All public domain types that become durable contracts must include English KDoc.
Value/data classes must implement `Serializable` and define `serialVersionUID`.

## Persistence And Transaction Boundary

The implementation should prefer Spring Data JPA for the application state
because the existing Spring Modulith examples already use the Spring Boot/JPA
path and Spring Modulith's event publication registry naturally integrates with
Spring transactions.

PostgreSQL Testcontainers is mandatory for default tests. The implementation
must use the bluetape4k Testcontainers helper, such as
`PostgreSQLServer.Launcher.postgres`, if available in the current dependency
boundary. If the exact helper name differs, implementation must inspect
`bluetape4k-testcontainers` and record the chosen helper in the plan and DoD
evidence. Raw `GenericContainer` is not allowed.

## JaVers Boundary

The module should reuse `bluetape4k-javers` DDD helpers where they fit:

- `AggregateRoot` for the audited aggregate contract.
- `DomainEvent` for event metadata.
- `AggregateRepository` for save + JaVers commit + event publication behavior.
- `SpringApplicationEventDomainEventPublisher` for after-commit Spring event
  publication.

The implementation must not copy those helpers into the workshop module unless
dependency resolution proves the artifact is unavailable through the current
root BOM. If a fallback is needed, it must be explained in the plan before
implementation.

## Spring Modulith Boundary

The module will contain at least two logical modules:

- `orders`: aggregate, command service, audit repository, JaVers query service.
- `fulfillment`: `@ApplicationModuleListener` for `OrderApproved`.

The listener must demonstrate the publication registry behavior:

- successful approval eventually creates a fulfillment reservation.
- listener failure leaves an incomplete/failed publication that can be inspected.
- rollback before commit does not publish the domain event.

## Documentation And Diagrams

README files must be learner-facing:

- `README.md` in English.
- `README.ko.md` in natural Korean with equivalent source content.
- Language switch directly below the title.
- Validation command and PostgreSQL/Testcontainers prerequisite.
- Explanation table comparing:
  - domain events,
  - Spring Modulith publication,
  - transactional outbox,
  - JaVers audit.

Diagrams are mandatory:

- Architecture diagram: aggregate command, PostgreSQL, JaVers, publication
  registry, fulfillment listener.
- Sequence diagram: place/approve order, transaction commit, after-commit event
  publication, listener execution, audit query.

All diagram assets must pass the full `bluetape4k-diagram` checklist, generated
SVG/PNG validation, and full-size PNG eye inspection. A script PASS alone is not
enough evidence.

## CI And Registration

The new module requires registration updates:

- Root `README.md` and `README.ko.md`.
- `AGENTS.md` module list if the module description needs narrowing.
- `.github/workflows/Examples.yml` path filters.
- Container-backed Examples workflow command and artifacts.
- `scripts/smoke-validate.sh` container/full lane.
- `scripts/smoke-validate.sh stale-check` expected project count.
- Diagram validator allowlists if new architecture/sequence SVG names are
  included in strict validators.

Because PostgreSQL Testcontainers is in the default path, tests must run
serially with other container-backed tests.

## Test Plan

Required tests:

- aggregate invariant rejects invalid lines and invalid state transitions.
- placing an order persists the aggregate and records a JaVers snapshot.
- approving an order records a second snapshot and exposes a useful diff.
- approval publishes `OrderApproved` only after transaction commit.
- fulfillment listener creates a reservation on successful publication.
- simulated listener failure leaves an incomplete/failed Modulith publication
  and can be resubmitted when practical.
- rollback before commit prevents fulfillment side effects and event
  publication.

Verification commands planned for implementation:

```bash
./gradlew :spring-modulith-ddd-order-audit:compileKotlin :spring-modulith-ddd-order-audit:compileTestKotlin --warning-mode all --console=plain
./gradlew :spring-modulith-ddd-order-audit:test --warning-mode all --console=plain --max-workers=1
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh data-access-full
./scripts/smoke-validate.sh diagram-qa
actionlint .github/workflows/Examples.yml
git diff --check
```

## Risks And Mitigations

| Risk | Mitigation |
|---|---|
| PostgreSQL Testcontainers increases CI cost | Put the module in the container-backed lane and keep tests focused. |
| JaVers persistence plumbing distracts from the lesson | Use in-memory JaVers unless a stable PostgreSQL-backed path is simple through the current BOM. |
| Event publication timing is flaky | Use Spring Modulith testing APIs and Awaitility-style bounded assertions already used in sibling examples. |
| Raw container setup bypasses bluetape4k conventions | Use `bluetape4k-testcontainers` PostgreSQL helper; document the helper in DoD. |
| Diagram regressions repeat previous misses | Treat rendered PNG eye inspection as mandatory final evidence after every diagram coordinate change. |
| Duplicate scope with `exposed-workshop` | Keep this module focused on bluetape4k-workshop's Spring Modulith + JaVers integration, not Exposed DDD repository mechanics. |

## Acceptance Criteria

- New `:spring-modulith-ddd-order-audit` Gradle module is discovered.
- Default tests use PostgreSQL Testcontainers through bluetape4k helper
  infrastructure.
- Module uses root `bluetape4k-dependencies` BOM only; new bluetape4k aliases do
  not pin versions.
- Tests cover aggregate invariants, event publication, rollback behavior, and
  JaVers history/diff query.
- README.md and README.ko.md include diagrams, validation commands, and the
  domain-events/Modulith/outbox/JaVers comparison.
- CI/smoke registration covers the new module in the correct container-backed
  lane.
- Diagrams pass `bluetape4k-diagram` checklist and full-size PNG eye inspection.
- PR metadata mirrors issue #322 and final PR body ends with `## DoD Status`.
