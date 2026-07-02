# Issue 326 EventBridge Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local-first `aws/eventbridge-scheduler` workshop module that teaches EventBridge envelope mapping and Scheduler-style delayed workflow boundaries.

**Architecture:** A Spring Boot example service maps `OrderWorkflowRequest` into an EventBridge event and a local Scheduler request. EventBridge uses AWS SDK v2 `PutEventsRequestEntry` behind a workshop-local `EventBridgePublisher` because the `bluetape4k-dependencies 1.3.1` artifact does not expose the newer `bluetape4k-aws` EventBridge Spring wrapper yet; Scheduler also stays a workshop-local boundary because upstream Scheduler wrapper support is not available yet.

**Tech Stack:** Kotlin 2.3 language level, Java 21, Spring Boot 4, AWS SDK v2 EventBridge, Jackson 3, bluetape4k core/assertions/JUnit5/coroutines test helpers.

---

## File Structure

- Create `aws/eventbridge-scheduler/build.gradle.kts`.
- Create `aws/eventbridge-scheduler/src/main/kotlin/io/bluetape4k/workshop/aws/eventbridge/`.
- Create `EventBridgeSchedulerApplication.kt`, `OrderWorkflowModels.kt`, `OrderWorkflowProperties.kt`, `WorkflowScheduler.kt`, `LocalWorkflowScheduler.kt`, `OrderWorkflowService.kt`.
- Create tests under matching `src/test/kotlin/.../eventbridge/`.
- Create `src/main/resources/application.yml`, `src/test/resources/junit-platform.properties`, and `src/test/resources/logback-test.xml`.
- Update `gradle/libs.versions.toml` with `aws2-eventbridge-lib` only.
- Update `aws/README.md`, `aws/README.ko.md`, root README locale set if AWS module index is present, `scripts/smoke-validate.sh`, `.github/workflows/Examples.yml`, and diagram validator scripts.
- Create diagram SVG/PNG assets under `docs/images/readme-diagrams/`.
- Create review and lesson artifacts under `docs/review/` and `docs/lessons/`.

## Tasks

### Task 1: Add RED tests for workflow mapping

- [x] Create `OrderWorkflowServiceTest.kt` with tests for:
  - EventBridge entry source, detail type, event bus, JSON detail, and trace header.
  - Scheduler schedule name/group/target/expression/payload.
  - Idempotency key and correlation ID propagation.
  - EventBridge failure skips Scheduler.
  - Scheduler failure keeps EventBridge published but reports Scheduler failed.
  - `CancellationException` is rethrown.
- [x] Run `./gradlew :aws-eventbridge-scheduler:test --tests "*OrderWorkflowServiceTest"`.
- [x] Expected RED: project or classes unresolved before implementation.

### Task 2: Implement the minimal module

- [x] Add Gradle module using Spring Boot conventions from `aws/cloudwatch-imds-observability`.
- [x] Add model data classes with `Serializable` and `serialVersionUID`.
- [x] Add `OrderWorkflowProperties` with local defaults.
- [x] Add `EventBridgePublisher`, `WorkflowScheduler`, and local capturing implementations.
- [x] Add `OrderWorkflowService` with validation helpers, cancellation rethrow, EventBridge mapping, Scheduler mapping, and failure reporting.
- [x] Add application/resources/test resources.
- [x] Run targeted test until green.

### Task 3: Add README and diagrams

- [x] Add module `README.md` and `README.ko.md` with language switch.
- [x] Update AWS README locale set with module guide/runtime/run entries.
- [x] Update root README locale set if it indexes AWS modules.
- [x] Add architecture and sequence SVGs, render PNGs with CairoSVG.
- [x] Confirm the generated `*-readme-architecture-01.svg` and `*-readme-sequence-01.svg`
  names are covered by the repo validators without legacy exceptions.
- [x] Run full `$bluetape4k-diagram` checklist including XML parse, render, marker/color/style, connector geometry, sequence style, and full-size visual inspection.

### Task 4: Register validation and CI

- [x] Update `scripts/smoke-validate.sh` `all-smoke`, `aws`, and `stale-check` expected project count.
- [x] Update `.github/workflows/Examples.yml` path filters, smoke test command, artifact paths, and comments.
- [x] Run `actionlint .github/workflows/Examples.yml`.

### Task 5: Verify

- [x] Run `./gradlew :aws-eventbridge-scheduler:compileKotlin :aws-eventbridge-scheduler:compileTestKotlin --warning-mode all --max-workers=1`.
- [x] Run `./gradlew :aws-eventbridge-scheduler:test --no-build-cache --rerun-tasks --max-workers=1`.
- [x] Run `./scripts/smoke-validate.sh aws`.
- [x] Run `./scripts/smoke-validate.sh stale-check`.
- [x] Run `node scripts/validate-readme-parity.mjs`.
- [x] Run `node scripts/validate-readme-language.mjs`.
- [x] Run `./scripts/smoke-validate.sh diagram-qa` equivalent with explicit new SVG paths.
- [x] Run `git diff --check`.

### Task 6: Review, lesson, PR, CI

- [x] Run Step 6-R 7-Tier review with P0/P1 convergence.
- [x] Save `docs/review/2026-07-02-issue-326-eventbridge-scheduler-review.md`.
- [x] Add `docs/lessons/2026-07-02-issue-326-eventbridge-scheduler.md`.
- [ ] Commit with Lore trailers.
- [ ] Create PR with body ending in `## DoD Status`.
- [ ] Verify PR milestone, assignee, labels, body, and CI checks.

## Plan Self-Review

- Spec coverage: all issue #326 acceptance criteria map to Tasks 1-5.
- Placeholder scan: no TODO/TBD placeholders are left as implementation steps.
- Type consistency: model/service names use `OrderWorkflow*`, `WorkflowScheduler`,
  and `EventBridgeScheduler` consistently.
- Risk: Scheduler real AWS integration is explicitly out of scope until
  `bluetape4k-aws` issue #310 lands.
