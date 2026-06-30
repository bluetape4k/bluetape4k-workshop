# CloudWatch IMDS Observability Workshop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a learner-friendly Spring Boot AWS observability example for
issue #317 that demonstrates CloudWatch metrics, CloudWatch Logs, Micrometer
snapshot publishing, and explicit safe IMDS reads without real AWS credentials
in default tests.

**Spec:** `docs/superpowers/specs/2026-06-30-issue-317-cloudwatch-imds-observability-design.md`

**Spec review:** `docs/review/2026-06-30-issue-317-spec-review.md`

**Architecture:** A local-first Spring Boot MVC/Actuator module exposes a small
telemetry API. The service records local Micrometer state, builds CloudWatch
metric/log requests, optionally reads safe IMDS helper values, and returns a
report that explains what was published or skipped. Local profile beans are
deterministic and credential-free; optional real AWS usage is documented as a
manual profile only.

**Tech Stack:** Kotlin, Spring Boot 4 MVC/Actuator, bluetape4k-aws Spring
interfaces, AWS SDK v2 CloudWatch/CloudWatch Logs/IMDS models, Micrometer,
JUnit 5, MockK, bluetape4k assertions.

---

## File Structure

- Create `aws/cloudwatch-imds-observability/build.gradle.kts`
- Create `aws/cloudwatch-imds-observability/src/main/kotlin/io/bluetape4k/workshop/aws/observability/*`
- Create `aws/cloudwatch-imds-observability/src/main/resources/application.yml`
- Create `aws/cloudwatch-imds-observability/src/test/kotlin/io/bluetape4k/workshop/aws/observability/*`
- Create `aws/cloudwatch-imds-observability/src/test/resources/junit-platform.properties`
- Create `aws/cloudwatch-imds-observability/src/test/resources/logback-test.xml`
- Create `aws/cloudwatch-imds-observability/README.md`
- Create `aws/cloudwatch-imds-observability/README.ko.md`
- Modify `gradle/libs.versions.toml`
- Modify `aws/README.md`
- Modify `aws/README.ko.md`
- Modify root `README.md`
- Modify root `README.ko.md`
- Modify `.github/workflows/Examples.yml`
- Modify `scripts/smoke-validate.sh`
- Create SVG/PNG diagrams under `docs/images/readme-diagrams/`
- Create a short lesson under `docs/lessons/` after implementation review

## Dependency And API Guard

- [ ] Add versionless version-catalog library aliases for AWS SDK v2 modules:
      `aws2-cloudwatch-lib`, `aws2-cloudwatchlogs-lib`, and `aws2-imds-lib`.
- [ ] Use the root `bluetape4k-dependencies` BOM only; do not add any
      repository-local bluetape4k version.
- [ ] Verify compile against the resolved `bluetape4k-aws` artifact APIs, not
      only sibling source evidence.
- [ ] Confirm public operations and model types used by the module:
      `CloudWatchOperations`, `CloudWatchLogsOperations`,
      `CloudWatchMeterPublishingOperations`, `ImdsOperations`,
      `MetricDatum`, `PutLogEventsRequest`, and `InputLogEvent`.

## Tasks

### Task 1: Module Skeleton

- [ ] Create `build.gradle.kts` with `kotlin.spring`, Spring Boot MVC/Actuator,
      configuration processor, bluetape4k-aws, AWS SDK CloudWatch/Logs/IMDS,
      Micrometer, MockK, JUnit 5, bluetape4k assertions, and Spring Boot test
      dependencies.
- [ ] Add `ObservabilityApplication.kt`.
- [ ] Add `application.yml` with local defaults:
      `bluetape4k.workshop.aws.observability.namespace`,
      `logGroupName`, `logStreamName`, `serviceName`, and
      `metadata.enabled=false`.
- [ ] Add test resources matching existing Spring Boot modules.
- [ ] Run `./gradlew projects --console=plain` and verify
      `:aws-cloudwatch-imds-observability` appears through `includeModules("aws", false, true)`.
- [ ] Run `./gradlew :aws-cloudwatch-imds-observability:compileKotlin --warning-mode all --console=plain`.

### Task 2: TDD Red Tests

- [ ] Add failing service tests before production implementation for successful
      telemetry publishing.
- [ ] Add failing tests for CloudWatch metric datum name, namespace, dimensions
      (`Outcome`, `Service`, `Source` only), and value mapping.
- [ ] Add failing tests for CloudWatch Logs group, stream, timestamp, sanitized
      JSON event fields, and absence of sensitive request data.
- [ ] Add failing tests for Micrometer counter/timer increments and selected
      meter snapshot publishing.
- [ ] Add failing tests for metric publish failure, logs publish failure, meter
      snapshot failure, and mixed partial-failure reports.
- [ ] Add failing tests proving `CancellationException` is rethrown from suspend
      publish paths.
- [ ] Add failing tests for IMDS default skip behavior.
- [ ] Add failing tests for explicit metadata opt-in reading only safe helper
      values such as instance id, region, and availability zone.
- [ ] Add failing tests asserting credential document paths such as
      `/latest/meta-data/iam/security-credentials/{role}` are never read.
- [ ] Run `./gradlew :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain`
      and record the expected red failures before implementation.

### Task 3: Domain And Properties

- [ ] Add serializable DTOs and models:
      `OrderTelemetryRequest`, `OrderTelemetryReport`, `TelemetryOutcome`,
      `PublishStatus`, `MetadataSnapshot`, and `TelemetryFailure`.
- [ ] Every new Kotlin `data class` must implement `java.io.Serializable` and
      define `serialVersionUID`.
- [ ] Add `AwsObservabilityProperties` with namespace, log group, log stream,
      service name, source name, metadata opt-in flag, and max free-form field
      length.
- [ ] Validate caller-controlled strings with bluetape4k validation helpers.
- [ ] Define partial-failure semantics:
      metric/log/meter failures are reported independently, metadata failures
      stay inside metadata status, and cancellation is never swallowed.

### Task 4: Local Operation Beans

- [ ] Add `LocalAwsObservabilityConfig` for the default/local profile.
- [ ] Provide deterministic local implementations or test fakes for
      `CloudWatchOperations`, `CloudWatchLogsOperations`,
      `CloudWatchMeterPublishingOperations`, and `ImdsOperations`.
- [ ] Ensure local beans do not create AWS SDK clients, do not read credentials,
      do not call IMDS, and are suitable for CI.
- [ ] Keep captured request state test-visible without turning it into a public
      production API.
- [ ] If optional real profile classes are added, keep them guarded by explicit
      profile/property conditions and document that CI does not run them.

### Task 5: Telemetry Service And HTTP Boundary

- [ ] Implement `OrderTelemetryService` to record Micrometer timer/counter state.
- [ ] Build CloudWatch `MetricDatum` with only low-cardinality dimensions:
      `Outcome`, `Service`, and `Source`.
- [ ] Build CloudWatch Logs events with sanitized fields:
      event id, outcome, service, source, elapsed time, and safe error summary.
- [ ] Do not log or return credentials, tokens, headers, environment values,
      full exception stacks, raw metadata documents, or high-cardinality IDs as
      CloudWatch dimensions.
- [ ] Publish a selected Micrometer meter snapshot through
      `CloudWatchMeterPublishingOperations`.
- [ ] Implement explicit metadata read only when the request or properties opt
      in; otherwise report metadata as skipped.
- [ ] Add `OrderTelemetryController` endpoints:
      `POST /api/aws-observability/orders` and
      `GET /api/aws-observability/metadata`.
- [ ] Add controller tests for success, failure, metadata skipped, metadata
      enabled, validation errors, and local profile wiring.
- [ ] Run `./gradlew :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain`.

### Task 6: README And Diagrams

- [ ] Write `aws/cloudwatch-imds-observability/README.md` and `README.ko.md`
      with source-equivalent learner flow:
      local mode, run commands, endpoint examples, successful and failed report
      examples, optional real AWS profile, required environment variables,
      cost/cleanup warning, and IMDS credential boundary.
- [ ] Update `aws/README.md` and `aws/README.ko.md` to include the new example.
- [ ] Update root `README.md` and `README.ko.md` module tables.
- [ ] Create layered architecture diagram:
      `docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.svg/png`.
- [ ] Create best-practices sequence diagram:
      `docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg/png`.
- [ ] Use official AWS CloudWatch and CloudWatch Logs icons from the shared wiki
      catalog only on real AWS managed-service nodes.
- [ ] Visually distinguish local fake operation beans from real AWS managed
      services.
- [ ] Sequence diagram requirements:
      numbered call labels, visible labels, transparent `alt`/`else` bodies,
      branch-specific line colors, no text overlap, and readable full-size PNG.
- [ ] Run README language/parity scripts and every applicable
      `$bluetape4k-diagram` checklist/audit.
- [ ] Inspect every touched PNG at full size and record the visual evidence.

### Task 7: CI And Smoke Registration

- [ ] Add `.github/workflows/Examples.yml` path filters for
      `aws/cloudwatch-imds-observability/**` under `push` and `pull_request`.
- [ ] Add `:aws-cloudwatch-imds-observability:test` to the H2/default smoke job.
- [ ] Add test result artifact paths for
      `aws/cloudwatch-imds-observability/build/test-results/test/*.xml` and
      `aws/cloudwatch-imds-observability/build/reports/tests/test/`.
- [ ] Add `:aws-cloudwatch-imds-observability:test` to
      `scripts/smoke-validate.sh all-smoke`.
- [ ] Add the same module to the `observability` group because it is
      credential-free and no-container.
- [ ] Increase stale-check expected project count from `88` to `89`.
- [ ] Run `actionlint .github/workflows/Examples.yml`.
- [ ] Run `./scripts/smoke-validate.sh stale-check`.

### Task 8: Verification And Review

- [ ] Run `./gradlew :aws-cloudwatch-imds-observability:compileKotlin --warning-mode all --console=plain`.
- [ ] Run `./gradlew :aws-cloudwatch-imds-observability:compileTestKotlin --warning-mode all --console=plain`.
- [ ] Run `./gradlew :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain`.
- [ ] Run `./gradlew projects --console=plain`.
- [ ] Run `node scripts/validate-readme-language.mjs`.
- [ ] Run `node scripts/validate-readme-parity.mjs`.
- [ ] Run `node scripts/validate-readme-architecture-diagrams.mjs`.
- [ ] Run `node scripts/validate-sequence-diagrams.mjs`.
- [ ] Run `$bluetape4k-diagram` geometry, endpoint, connector, and sequence
      style audits on the new SVGs.
- [ ] Render SVG to PNG with the repo-approved renderer and inspect each PNG at
      full size.
- [ ] Run `actionlint .github/workflows/Examples.yml`.
- [ ] Run `./scripts/smoke-validate.sh stale-check`.
- [ ] Run `git diff --check`.
- [ ] Run Step 6-R implementation review and fix every P0/P1.
- [ ] Add a short lesson under `docs/lessons/`.

### Task 9: PR And CI

- [ ] Commit with Lore protocol trailers.
- [ ] Push the feature branch.
- [ ] Create PR against `develop`, assigned to `debop`.
- [ ] Copy issue #317 milestone and labels onto the PR.
- [ ] Ensure the PR body includes issue link, summary, verification evidence,
      and final `## DoD Status` section.
- [ ] Verify live PR metadata with `gh pr view --json assignees,labels,milestone,body`.
- [ ] Monitor required checks; fix any failures before asking for merge.

## Self-Review

- Spec coverage: every issue acceptance criterion maps to Tasks 1-9.
- Step 2-R P2 carry-forward: partial failures, cancellation, no IMDS credential
  document reads, resolved API verification, exact CI/smoke edits, and diagram
  checklist/visual inspection are explicit tasks.
- Ordering: dependency/API guard and TDD red tests precede production
  implementation.
- Public docs: README locale pair, AWS README locale pair, and root README
  locale pair are included.
- Default test boundary: no real AWS, no EC2 runtime, no LocalStack, and no
  Testcontainers in default verification.
- Placeholder scan target: no unresolved placeholder markers or unbounded
  "later" work should remain before Step 3-R closure.
