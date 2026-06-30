# Ktor Exposed REST Workshop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a PostgreSQL-backed Ktor + Exposed REST example that demonstrates
`bluetape4k-exposed-ktor` transactions, error mapping, and learner-friendly
route documentation.

**Spec:** `docs/superpowers/specs/2026-07-01-issue-319-ktor-exposed-rest-design.md`

**Architecture:** Ktor routes own only HTTP and validation. `KtorExposedRestResources`
owns Hikari, Exposed `Database`, and the blocking dispatcher. Each route enters
Exposed through `ApplicationCall.exposedJdbcTransaction(...)`. Tests supply
PostgreSQL through `PostgreSQLServer.Launcher.postgres`.

**Tech Stack:** Kotlin, Ktor server/test host, kotlinx serialization, Exposed
JDBC, HikariCP, PostgreSQL JDBC driver, bluetape4k-ktor-core,
bluetape4k-exposed-ktor, bluetape4k-testcontainers, JUnit 5, bluetape4k
assertions, CairoSVG-rendered README diagrams.

---

## File Structure

- Create `ktor/exposed-rest/build.gradle.kts`
- Create `ktor/exposed-rest/src/main/kotlin/io/bluetape4k/workshop/ktor/exposedrest/*`
- Create `ktor/exposed-rest/src/main/resources/logback.xml`
- Create `ktor/exposed-rest/src/test/kotlin/io/bluetape4k/workshop/ktor/exposedrest/*`
- Create `ktor/exposed-rest/src/test/resources/junit-platform.properties`
- Create `ktor/exposed-rest/src/test/resources/logback-test.xml`
- Create `ktor/exposed-rest/README.md`
- Create `ktor/exposed-rest/README.ko.md`
- Modify `gradle/libs.versions.toml`
- Modify root `README.md`
- Modify root `README.ko.md`
- Modify `ktor/README.md` and `ktor/README.ko.md` if they exist
- Modify `.github/workflows/Examples.yml`
- Modify `scripts/smoke-validate.sh`
- Create `docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.svg/png`
- Create `docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.svg/png`
- Create `docs/review/2026-07-01-issue-319-implementation-review.md`
- Create `docs/lessons/2026-07-01-issue-319-ktor-exposed-rest.md`

## Dependency and API Guard

- [ ] Add catalog aliases for:
      `bluetape4k-ktor-core`, `bluetape4k-ktor-testing`, and `exposed-ktor`.
- [ ] Keep all bluetape4k aliases versionless under the root BOM.
- [ ] Use existing Ktor BOM and Exposed/Hikari/PostgreSQL aliases.
- [ ] Verify compile against:
      `installBluetape4kExposedKtor`,
      `StatusPagesConfig.bluetape4kExposedErrors`,
      `ApplicationCall.exposedJdbcTransaction`, and
      `PostgreSQLServer.Launcher.postgres`.

## Task 1: Module Skeleton

**Complexity:** medium

**Files:**
- Create `ktor/exposed-rest/build.gradle.kts`
- Create resource files under `ktor/exposed-rest/src/main/resources/` and
  `src/test/resources/`
- Modify `gradle/libs.versions.toml`

- [ ] Create the Gradle build with Ktor server, Ktor test host, kotlinx
      serialization, Exposed JDBC, HikariCP, PostgreSQL driver,
      bluetape4k-ktor-core, bluetape4k-exposed-ktor, bluetape4k-testcontainers,
      and bluetape4k assertions.
- [ ] Add `application` main class configuration.
- [ ] Add JUnit/logback test resources consistent with neighboring modules.
- [ ] Run `./gradlew projects --console=plain` and verify
      `:ktor-exposed-rest`.
- [ ] Run `./gradlew :ktor-exposed-rest:compileKotlin --warning-mode all --console=plain`.

## Task 2: TDD Red Tests

**Complexity:** high

**Files:**
- Create tests under
  `ktor/exposed-rest/src/test/kotlin/io/bluetape4k/workshop/ktor/exposedrest/`

- [ ] Add a failing test for create/list/read/update/delete routes backed by
      PostgreSQL.
- [ ] Add a failing test proving rollback leaves no inserted row.
- [ ] Add a failing test proving Exposed transaction errors are mapped to safe
      responses.
- [ ] Add a failing test proving direct SQL failures are sanitized and do not
      leak JDBC URLs, usernames, or passwords.
- [ ] Add a failing test for Exposed readiness route.
- [ ] Add a failing test proving cancellation propagates instead of being
      converted to a database error, if exposed by Ktor test host.
- [ ] Run
      `./gradlew :ktor-exposed-rest:test --warning-mode all --console=plain --max-workers=1`
      and record expected red failures before implementation.

## Task 3: Application and Persistence

**Complexity:** high

**Files:**
- Create `KtorExposedRestApplication.kt`
- Create `KtorExposedRestResources.kt`
- Create `BookModels.kt`
- Create `BookRepository.kt`
- Create `BookRoutes.kt`

- [ ] Implement serializable request/response/error DTOs with
      `serialVersionUID`.
- [ ] Implement `BookRepository` using Exposed JDBC and a simple PostgreSQL
      table.
- [ ] Implement resource creation from explicit JDBC URL, username, password,
      and driver class.
- [ ] Reset schema at resource creation for deterministic workshop tests.
- [ ] Close Hikari and dispatcher on Ktor application stop.
- [ ] Install Ktor content negotiation and `StatusPages`.
- [ ] Install `bluetape4kExposedErrors()` and exposed health routes.
- [ ] Implement CRUD routes.
- [ ] Implement rollback, direct SQL failure, and cancellation demonstration
      routes.
- [ ] Run focused module tests serially until green.

## Task 4: README and Diagrams

**Complexity:** high

**Files:**
- Create `ktor/exposed-rest/README.md`
- Create `ktor/exposed-rest/README.ko.md`
- Modify root/Ktor README locale pairs
- Create SVG/PNG diagrams under `docs/images/readme-diagrams/`

- [ ] Write English README with overview, architecture, dependencies, route
      examples, PostgreSQL Testcontainers note, focused test command, and
      transaction/error sections.
- [ ] Write source-equivalent Korean README with natural Korean technical
      prose.
- [ ] Update root/Ktor module tables without changing unrelated module copy.
- [ ] Create top-to-bottom architecture diagram with visible layer bands and
      official PostgreSQL icon from the shared catalog.
- [ ] Create best-practices sequence diagram with numbered labels above lines,
      transparent branch bodies, branch-specific muted colors, activation bars,
      and color-matched arrowheads.
- [ ] Render SVGs with `~/.local/bin/cairosvg <svg> -o <png> -s 2`.
- [ ] Run `xmllint --noout` on new SVGs.
- [ ] Run the full `$bluetape4k-diagram` checklist, including geometry,
      endpoint, mixed-corner, connector, sequence-style, marker-color,
      label-over-line, legend, icon, and visual checks as applicable.
- [ ] Open every touched PNG at full size for eye inspection and record
      evidence.

## Task 5: CI and Smoke Registration

**Complexity:** medium

**Files:**
- Modify `.github/workflows/Examples.yml`
- Modify `scripts/smoke-validate.sh`

- [ ] Add `:ktor-exposed-rest:test` to the container-backed examples lane.
- [ ] Keep `:ktor-exposed-rest:test` out of Docker-free smoke lanes.
- [ ] Add test artifact upload paths for the new module.
- [ ] Add the module to the relevant validation script group.
- [ ] Adjust stale-check expected project count after confirming
      `./gradlew projects`.
- [ ] Run `actionlint .github/workflows/Examples.yml`.
- [ ] Run `./scripts/smoke-validate.sh stale-check`.
- [ ] Run the edited container validation command serially.

## Task 6: Review, Lessons, and PR

**Complexity:** medium

**Files:**
- Create `docs/review/2026-07-01-issue-319-implementation-review.md`
- Create `docs/lessons/2026-07-01-issue-319-ktor-exposed-rest.md`

- [ ] Run a self-review focused on correctness, transaction boundaries,
      cancellation, SQL error sanitization, Testcontainers lifecycle, README
      clarity, and diagram checklist coverage.
- [ ] Record implementation review findings and fixes.
- [ ] Record a short lesson with context, decision, outcome, verification
      evidence, and future-agent guidance.
- [ ] Run `git diff --check`.
- [ ] Commit with Lore protocol.
- [ ] Create a PR that resolves #319, assign `debop`, and copy issue milestone
      and labels.
- [ ] Verify live PR body with `gh pr view --json body`; the final `##`
      heading must be `## DoD Status`.
- [ ] Verify live PR metadata with `gh pr view`.

## Final Verification Checklist

- [ ] `./gradlew :ktor-exposed-rest:test --warning-mode all --console=plain --max-workers=1`
- [ ] `./gradlew :ktor-exposed-rest:compileKotlin --warning-mode all --console=plain`
- [ ] `./gradlew projects --console=plain`
- [ ] `./scripts/smoke-validate.sh stale-check`
- [ ] `./scripts/smoke-validate.sh data-access-full`
- [ ] `./scripts/smoke-validate.sh diagram-qa`
- [ ] `actionlint .github/workflows/Examples.yml`
- [ ] `xmllint --noout docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.svg`
- [ ] `xmllint --noout docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.svg`
- [ ] `git diff --check`

## Stop Conditions

- Stop and repair if any workflow gate is skipped or weakly evidenced.
- Stop and redesign if `bluetape4k-exposed-ktor` APIs differ from the verified
  local source.
- Stop and narrow the test if Ktor test host cannot expose cancellation
  propagation directly; document the exact limitation and keep upstream helper
  behavior covered by transaction/error tests.
- Stop before merge. PR creation is in scope; merge requires the user's later
  instruction.
