# Issue 327 SQS/SNS Coroutine Messaging Implementation Plan

> **For agentic workers:** implement one task at a time and update this
> checklist as evidence is collected. Do not mark diagram gates complete without
> concrete `$bluetape4k-diagram` ledger output and full-size PNG inspection.

**Goal:** Build a local-first `aws/sqs-sns-coroutines` workshop module that
teaches SNS publish, SQS coroutine consumption, retry/dead-letter
classification, and Micrometer outcome metrics.

**Architecture:** Spring Boot service code uses `SnsOperations` and
`SqsOperations` from `bluetape4k-aws-spring-boot`. The default runtime and tests
use in-memory local operations so the module does not need AWS credentials.

**Tech Stack:** Kotlin 2.4, Java 21, Spring Boot 4, bluetape4k core/assertions,
bluetape4k-aws Spring Boot SQS/SNS operations, Jackson 3, Micrometer core, JUnit
5, MockK where useful.

## File Structure

- Create `aws/sqs-sns-coroutines/build.gradle.kts`.
- Create `aws/sqs-sns-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/sqssns/`.
- Create application, properties, models, metrics, handler, service, and local
  operations config files.
- Create tests under matching `src/test/kotlin/.../sqssns/`.
- Create `src/main/resources/application.yml`, `src/test/resources/junit-platform.properties`,
  and `src/test/resources/logback-test.xml`.
- Update `gradle/libs.versions.toml` with AWS SDK v2 SQS/SNS aliases if needed.
- Update module/root README locale sets, `scripts/smoke-validate.sh`, and
  `.github/workflows/Examples.yml`.
- Create SVG/PNG diagrams under `docs/images/readme-diagrams/`.
- Create review and lesson artifacts under `docs/review/` and `docs/lessons/`.

## Tasks

### Task 1: Add RED tests for publish/consume behavior

- [x] Create `OrderNotificationMessagingServiceTest.kt` with tests for:
  - SNS publish request topic ARN, subject, JSON body, idempotency key, and correlation ID.
  - SQS consume success deletes the message and records `acked`.
  - Handler failure before max receive count changes visibility and records `retry`.
  - Max receive count classifies as `dead-letter`.
  - Metrics counters/timers include stable low-cardinality tags.
  - `CancellationException` is rethrown.
- [x] Run `./gradlew :aws-sqs-sns-coroutines:test --tests "*OrderNotificationMessagingServiceTest"`.
- [x] Expected RED: module/classes unresolved before implementation.

### Task 2: Implement the module

- [x] Add Gradle module using Spring Boot conventions from existing AWS modules.
- [x] Add model data classes with `Serializable` and `serialVersionUID`.
- [x] Add `SqsSnsMessagingProperties` with local-safe defaults.
- [x] Add `OrderNotificationMetrics` helper.
- [x] Add `OrderNotificationMessagingService` with validation helpers,
  cancellation rethrow, SNS mapping, SQS processing, retry/dead-letter
  classification, and metrics.
- [x] Add local in-memory `SnsOperations` and `SqsOperations` beans for default
  runtime only when real beans are absent.
- [x] Run targeted test until green.

### Task 3: Add README and diagrams

- [x] Add module `README.md` and `README.ko.md` with language switch.
- [x] Update AWS README locale set and root README locale set.
- [x] Add architecture and sequence SVGs, render PNGs with CairoSVG.
- [x] Use AWS official SNS/SQS icons from the local catalog.
- [x] Run full `$bluetape4k-diagram` checklist including XML parse, render,
  marker/color/style, connector geometry, sequence style, and full-size visual
  inspection.

### Task 4: Register validation and CI

- [x] Update `scripts/smoke-validate.sh` `all-smoke`, `aws`, and `stale-check`
  expected project count.
- [x] Update `.github/workflows/Examples.yml` path filters, smoke command,
  artifact paths, and comments.
- [x] Run `actionlint .github/workflows/Examples.yml`.

### Task 5: Verify

- [x] Run `./gradlew :aws-sqs-sns-coroutines:compileKotlin :aws-sqs-sns-coroutines:compileTestKotlin --warning-mode all --max-workers=1`.
- [x] Run `./gradlew :aws-sqs-sns-coroutines:test --no-build-cache --rerun-tasks --max-workers=1`.
- [x] Run `./scripts/smoke-validate.sh aws`.
- [x] Run `./scripts/smoke-validate.sh stale-check`.
- [x] Run `node scripts/validate-readme-parity.mjs`.
- [x] Run `node scripts/validate-readme-language.mjs`.
- [x] Run `./scripts/smoke-validate.sh diagram-qa` or targeted wrapper command.
- [x] Run `git diff --check`.

### Task 6: Review, lesson, PR, CI

- [x] Run Step 6-R 7-Tier review with P0/P1 convergence.
- [x] Save `docs/review/2026-07-02-issue-327-sqs-sns-coroutines-review.md`.
- [x] Add `docs/lessons/2026-07-02-issue-327-sqs-sns-coroutines.md`.
- [ ] Commit with Lore trailers.
- [ ] Create PR with body ending in `## DoD Status`.
- [ ] Verify PR milestone, assignee, labels, body, and CI checks.

## Verification Evidence

- Targeted compile/test: `OrderNotificationMessagingServiceTest` 7 tests passed,
  `BUILD SUCCESSFUL`.
- Re-run test: `./gradlew :aws-sqs-sns-coroutines:test --no-build-cache
  --rerun-tasks --max-workers=1 --console=plain` -> 7 tests passed,
  `BUILD SUCCESSFUL`.
- AWS smoke: `./scripts/smoke-validate.sh aws` -> `BUILD SUCCESSFUL`.
- All smoke: `./scripts/smoke-validate.sh all-smoke` -> `BUILD SUCCESSFUL`.
- Stale check: active modules `97 (expected: 97)`, no stale refs, no broken
  image links.
- README parity/language: `failures=0`, `offenders=0`, `totalHits=0`.
- Diagram QA: targeted wrapper for the architecture and sequence SVGs passed
  with `targets=2 weak_reference_rows=0`; full-size PNG eye inspection passed.
- Workflow/whitespace: `actionlint .github/workflows/Examples.yml` and
  `git diff --check` produced no output.

## Plan Self-Review

- Spec coverage: all issue #327 acceptance criteria map to Tasks 1-5.
- Placeholder scan: no TODO/TBD placeholders are left as implementation steps.
- Type consistency: module, package, and class names consistently use
  `sqs-sns-coroutines`, `sqssns`, and `OrderNotification*`.
- Risk: real AWS/LocalStack validation is explicitly opt-in, not default CI.
