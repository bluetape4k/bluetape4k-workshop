# Issue #323 - Spring Modulith Module Boundaries Design

## Context

`bluetape4k-workshop` milestone `1.3.1` includes issue
[#323](https://github.com/bluetape4k/bluetape4k-workshop/issues/323), which
asks for a learner-facing Spring Modulith example focused on module-boundary
verification.

The example must show:

- Four application modules: `catalog`, `ordering`, `payment`, and
  `notification`.
- Explicit allowed dependency directions.
- Spring Modulith `ApplicationModules.verify()` checks.
- A rejected direct dependency that proves the boundary test is meaningful.
- Cross-module event publication instead of direct service calls.
- English and Korean README files plus diagrams that explain visibility,
  event contracts, and refactoring signals.
- Local deterministic tests with no external infrastructure.

## Current Evidence

- Live issue #323 is open, assigned to `debop`, labeled for documentation,
  Spring Boot, and architecture-extension work, and attached to milestone
  `1.3.1`.
- `settings.gradle.kts` auto-registers child modules under `spring-modulith/`,
  so `spring-modulith/module-boundaries` becomes
  `:spring-modulith-module-boundaries`.
- `spring-modulith/jpa-demo` already uses
  `ApplicationModules.of(SpringModulith::class.java).verify()`, but it does not
  isolate allowed dependency rules or an intentionally invalid fixture.
- `spring-modulith/events-deep-dive` already teaches event publication and
  `@ApplicationModuleTest`, but it is not a focused boundary-verification lab.
- Spring Modulith documentation confirms that `ApplicationModules.verify()`
  checks module arrangement rules, including cycles, access to internal types,
  and allowed dependencies. Kotlin modules can declare package metadata with a
  package-local `@PackageInfo` class annotated by `@ApplicationModule` or
  `@NamedInterface`.
- The `exposed-workshop` DDD Modulith boundary lesson proved a useful pattern:
  a valid module graph plus a test-only invalid fixture that imports another
  module's internal type and fails with Spring Modulith `Violations`.

## Approved Direction

Create a new `spring-modulith/module-boundaries` module with an in-memory
workflow. Do not use H2, PostgreSQL, Redis, Kafka, or any other infrastructure
because issue #323 is specifically about module visibility and event contracts,
not persistence.

The example will use Spring's event publisher and Spring Modulith module
metadata. This keeps the learner path deterministic while still showing the
architectural rule: modules communicate by exported APIs or events, not by
reaching into internal packages.

## Architecture

The valid application graph is:

1. `catalog` owns item availability and exports a named `catalog :: api`
   interface for read-only catalog lookup.
2. `ordering` depends only on `catalog :: api`, validates an order request, and
   publishes `ordering.events.OrderPlacedEvent`.
3. `payment` depends only on `ordering :: events` and records payment
   authorization from the event payload.
4. `notification` depends only on `ordering :: events` and records customer
   notifications from the event payload.

The invalid fixture is test-only and intentionally imports an `ordering`
internal type from `payment`. `ApplicationModules.verify()` must reject that
fixture with `Violations`.

## Design Alternatives

### Option A - New in-memory boundary-verification lab

This is the selected approach.

Pros:

- Directly matches issue #323.
- Keeps tests fast and deterministic.
- Avoids persistence details that would distract from module visibility.
- Allows README diagrams to focus on allowed dependencies and rejected imports.

Cons:

- Does not demonstrate transactional event publication or the event publication
  registry. Those concepts are already covered by adjacent Spring Modulith
  examples.

### Option B - Extend `spring-modulith/jpa-demo`

Pros:

- Reuses existing Spring Modulith application structure.

Cons:

- Mixes boundary verification with JPA concerns.
- Harder for learners to isolate the rule being demonstrated.
- Risks changing an older example instead of delivering the requested focused
  module.

### Option C - Copy the persistence-heavy DDD boundary pattern

Pros:

- Similar to the previous `exposed-workshop` lesson and could include richer
  domain logic.

Cons:

- Adds infrastructure and persistence noise.
- Conflicts with the issue requirement for local deterministic execution.

## Module Shape

Expected files:

- `spring-modulith/module-boundaries/build.gradle.kts`
- `spring-modulith/module-boundaries/README.md`
- `spring-modulith/module-boundaries/README.ko.md`
- `spring-modulith/module-boundaries/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/boundaries/`
- `spring-modulith/module-boundaries/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/boundaries/`
- `spring-modulith/module-boundaries/src/test/resources/junit-platform.properties`
- `spring-modulith/module-boundaries/src/test/resources/logback-test.xml`
- README diagram SVG/PNG assets under `docs/images/readme-diagrams/`

Expected package prefix:

```text
io.bluetape4k.workshop.spring.modulith.boundaries
```

## Module Contracts

`catalog`:

- `catalog.api.CatalogItemSnapshot`
- `catalog.api.CatalogLookup`
- `catalog.internal.InMemoryCatalogRepository`
- `@NamedInterface("api")` metadata in `catalog.api`

`ordering`:

- `ordering.OrderingService`
- `ordering.OrderRequest`
- `ordering.events.OrderPlacedEvent`
- `ordering.internal.OrderNumberGenerator`
- `@ApplicationModule(allowedDependencies = ["catalog :: api"])`
- `@NamedInterface("events")` metadata in `ordering.events`

`payment`:

- `payment.PaymentEventHandler`
- `payment.PaymentLedger`
- `@ApplicationModule(allowedDependencies = ["ordering :: events"])`

`notification`:

- `notification.NotificationEventHandler`
- `notification.NotificationOutbox`
- `@ApplicationModule(allowedDependencies = ["ordering :: events"])`

All public classes and interfaces must include concise English KDoc. Public
data classes must implement `Serializable` and define `serialVersionUID`.

## Test Strategy

Required tests:

- Valid graph test: `ApplicationModules.of(ModuleBoundariesApplication::class.java).verify()`.
- Invalid graph test: a test-only application fixture where payment imports
  `ordering.internal.LeakyOrderRepository`; verification must fail with
  Spring Modulith `Violations`.
- Event-flow test: placing an order publishes `OrderPlacedEvent`; payment and
  notification handlers update their own module-owned in-memory state without a
  direct service call.
- Guard tests for deterministic validation, such as missing catalog item or
  non-positive quantity.

The invalid fixture must stay under test source packages so the production
module graph remains clean.

## Documentation And Diagrams

README files must explain:

- Application modules and their exported named interfaces.
- Allowed dependency directions.
- Why direct imports into `internal` packages are rejected.
- How event contracts reduce coupling.
- How a failed boundary test guides refactoring.

Diagrams:

- `spring-modulith-module-boundaries-readme-architecture-01`: layered module
  graph with legend for allowed dependency, event contract, and rejected
  fixture edge.
- `spring-modulith-module-boundaries-readme-sequence-01`: order placement
  sequence with numbered call labels, event handoff, and a transparent
  rejected-boundary branch.

Both SVG and PNG files must pass the `bluetape4k-diagram` checklist, the
repo-local diagram QA wrapper, and full-size PNG visual inspection.

## Risks

- Kotlin package metadata classes must be placed in the correct package. A
  misplaced `@ApplicationModule` or `@NamedInterface` would weaken the test.
- A negative fixture that imports production packages can accidentally create
  unrelated violations. Keep it self-contained under a test-only root package.
- `@EventListener` handling is synchronous by default, which is acceptable for
  this deterministic workshop. README must not imply transactional publication
  registry semantics.
- Diagram style drift is a known failure mode. The sequence diagram must use
  the current best-practices palette, centered card text, matching line and
  arrowhead colors, numbered labels above the line, rounded orthogonal paths,
  and transparent grouped regions.

## Acceptance Criteria

- `:spring-modulith-module-boundaries:test` passes.
- `ApplicationModules.verify()` passes for the valid application.
- The invalid fixture fails with `Violations`.
- Event-flow tests prove payment and notification react through the order event.
- `README.md` and `README.ko.md` are source-equivalent and include generated
  PNG diagrams with SVG sources.
- Root README module catalog, Examples workflow, and smoke validation scripts
  include the new module.
- Diagram QA, README validation, `./gradlew projects`, targeted tests, and
  `git diff --check` pass before PR creation.
