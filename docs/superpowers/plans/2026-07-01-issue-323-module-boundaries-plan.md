# Spring Modulith Module Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `:spring-modulith-module-boundaries`, a learner-facing Spring
Modulith workshop example that verifies allowed module dependencies and proves
that cross-module communication happens through exported APIs or events.

**Architecture:** Four in-memory application modules. `ordering` depends only
on `catalog :: api`; `payment` and `notification` depend only on
`ordering :: events`. A test-only invalid fixture imports an `ordering`
internal type from `payment` and must fail `ApplicationModules.verify()` with
`Violations`.

**Tech Stack:** Kotlin 2.3, Java 21, Spring Boot 4, Spring Modulith 2.1,
`spring-modulith-starter-core`, `spring-modulith-starter-test`, JUnit 5,
bluetape4k assertions, generated SVG/PNG README diagrams.

---

## File Structure

- Create `spring-modulith/module-boundaries/build.gradle.kts`.
- Create `spring-modulith/module-boundaries/README.md`.
- Create `spring-modulith/module-boundaries/README.ko.md`.
- Create production packages under
  `spring-modulith/module-boundaries/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/boundaries/`.
- Create test packages under
  `spring-modulith/module-boundaries/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/boundaries/`.
- Create `src/test/resources/junit-platform.properties` and `logback-test.xml`.
- Create diagram assets:
  - `docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-architecture-01.{svg,png}`
  - `docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-sequence-01.{svg,png}`
- Modify root `README.md`, `README.ko.md`, `.github/workflows/Examples.yml`,
  and `scripts/smoke-validate.sh`.

## Task 1: Skeleton And Failing Tests

**Files:**

- Create: `spring-modulith/module-boundaries/build.gradle.kts`
- Create: `spring-modulith/module-boundaries/src/main/kotlin/.../ModuleBoundariesApplication.kt`
- Create: `spring-modulith/module-boundaries/src/test/kotlin/.../ApplicationModuleBoundaryTest.kt`
- Create: test-only invalid fixture packages.

- [ ] Add module build dependencies with `spring-modulith-starter-core`,
  `spring-modulith-starter-test`, Spring Boot test, and bluetape4k assertions.
- [ ] Add a minimal Spring Boot application entrypoint.
- [ ] Write failing valid-boundary verification test:

```kotlin
ApplicationModules.of(ModuleBoundariesApplication::class.java).verify()
```

- [ ] Write failing invalid-fixture verification test:

```kotlin
val violations = assertFailsWith<Violations> {
    ApplicationModules.of(
        InvalidBoundaryApplication::class.java,
        ImportOption.Predefined.DO_NOT_INCLUDE_JARS,
    ).verify()
}
violations.message shouldContain "LeakyOrderRepository"
```

- [ ] Run
  `./gradlew :spring-modulith-module-boundaries:test --console=plain --max-workers=1`
  and record the expected red result before implementation.

## Task 2: Production Module Metadata And Domain Flow

**Files:**

- Create production `catalog`, `ordering`, `payment`, and `notification`
  packages.
- Create metadata classes for `@ApplicationModule` and `@NamedInterface`.

- [ ] Implement `catalog.api.CatalogLookup` and
  `catalog.api.CatalogItemSnapshot`.
- [ ] Implement `catalog.internal.InMemoryCatalogRepository`.
- [ ] Implement `ordering.OrderRequest`, `ordering.OrderReceipt`, and
  `ordering.OrderingService`.
- [ ] Implement `ordering.events.OrderPlacedEvent` as the exported event
  contract.
- [ ] Implement `payment.PaymentEventHandler` and `PaymentLedger`.
- [ ] Implement `notification.NotificationEventHandler` and
  `NotificationOutbox`.
- [ ] Keep all cross-module interactions within the declared named interfaces.
- [ ] Add concise English KDoc to public types and make public data classes
  `Serializable`.

## Task 3: Integration Tests

**Files:**

- Create or update:
  `spring-modulith/module-boundaries/src/test/kotlin/.../OrderEventFlowTest.kt`

- [ ] Add event-flow test proving `OrderingService.placeOrder()` publishes
  `OrderPlacedEvent` and both payment and notification modules react without a
  direct service call.
- [ ] Add validation tests for missing catalog items and non-positive
  quantities.
- [ ] Run targeted tests and fix only production code needed for green.

## Task 4: Documentation And Diagrams

**Files:**

- Create `spring-modulith/module-boundaries/README.md`.
- Create `spring-modulith/module-boundaries/README.ko.md`.
- Create or update diagram generation script for the new assets.
- Create SVG/PNG architecture and sequence diagrams under
  `docs/images/readme-diagrams/`.

- [ ] README.md and README.ko.md include language switch, architecture diagram,
  sequence diagram, dependency rules, event contract explanation, and failure
  interpretation.
- [ ] Architecture diagram has layer grouping, consistent card alignment,
  legend for solid/event/rejected edges, official-style Spring Modulith labels,
  rounded orthogonal connectors, and no ambiguous connector crossings.
- [ ] Sequence diagram follows best-practices: muted palette, centered cards,
  numbered call labels above lines, labels not covering call lines, matching
  arrowhead and line colors, rounded orthogonal paths, transparent group
  regions, and no layout overlap.
- [ ] Run explicit diagram QA wrapper on both new SVGs and inspect both full
  PNGs visually.

## Task 5: Repository Registration

**Files:**

- Modify `README.md`.
- Modify `README.ko.md`.
- Modify `.github/workflows/Examples.yml`.
- Modify `scripts/smoke-validate.sh`.

- [ ] Add the new module to the root module catalog in both locales.
- [ ] Add the new module path and Gradle task to Examples workflow smoke
  coverage.
- [ ] Add the new module to `scripts/smoke-validate.sh` `all-smoke` and
  `spring-boot` groups.
- [ ] Update the stale project count after `./gradlew projects --console=plain`
  proves the expected count.

## Task 6: Verification

Run and record evidence:

- [ ] `./gradlew :spring-modulith-module-boundaries:test --console=plain --max-workers=1 --rerun-tasks`
- [ ] `./gradlew projects --console=plain`
- [ ] `node scripts/validate-readme-language.mjs`
- [ ] `node scripts/validate-readme-parity.mjs`
- [ ] `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-architecture-01.svg docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-sequence-01.svg`
- [ ] full-size PNG eye inspection for both generated diagrams.
- [ ] `bash scripts/smoke-validate.sh stale-check`
- [ ] `actionlint .github/workflows/Examples.yml`
- [ ] `git diff --check`

## Task 7: Review, Lesson, PR

- [ ] Run the Step 6-R review gate and fix any P0/P1/P2 issues.
- [ ] Add `docs/lessons/2026-07-01-issue-323-module-boundaries.md` with
  context, decision, outcome, verification evidence, and future-agent notes.
- [ ] Commit with the Lore commit protocol.
- [ ] Push the branch and create a PR against `develop`, assigned to `debop`,
  with milestone `1.3.1` and mirrored issue labels.
- [ ] Verify live PR metadata and body. The final Markdown `##` section must
  be `## DoD Status`.
- [ ] Wait for required CI and report merge readiness. Do not merge unless the
  user explicitly requests merge.
