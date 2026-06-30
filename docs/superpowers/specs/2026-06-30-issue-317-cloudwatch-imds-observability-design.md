# Issue #317 - AWS CloudWatch and IMDS Observability Workshop Spec

- Date: 2026-06-30
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/317
- Work type: Type A Full Feature
- Target repository: `bluetape4k/bluetape4k-workshop`
- Target module: `aws/cloudwatch-imds-observability`
- Gradle project: `:aws-cloudwatch-imds-observability`

## Problem

`bluetape4k-dependencies 1.3.1` promotes the `bluetape4k-aws` line that added
Spring Boot CloudWatch, CloudWatch Logs, EC2 Instance Metadata Service, and
Micrometer snapshot publishing helpers. The workshop still teaches AWS through
S3-oriented examples only:

- `aws/s3-spring-cloud`
- `aws/storage-abstraction`

The missing learning path is operational AWS observability: how an application
publishes custom CloudWatch metrics, sends structured CloudWatch Logs events,
records local Micrometer outcome meters, and reads EC2 metadata explicitly
without turning IMDS into an implicit credential strategy.

## Current Evidence

- Issue #317 is open in milestone `1.3.1`, assigned to `debop`, with labels
  `documentation`, `enhancement`, `difficulty:advanced`, `area:spring-boot`,
  and `area:observability-performance`.
- `settings.gradle.kts` auto-registers subdirectories under `aws/`; the new
  module path maps to `:aws-cloudwatch-imds-observability`.
- Root `build.gradle.kts` imports `platform(libs.bluetape4k.dependencies)` for
  subprojects, so the example must not pin a bluetape4k module version.
- `gradle/libs.versions.toml` already has `bluetape4k-aws`, Micrometer, and
  Spring Boot aliases. It lacks AWS SDK v2 aliases for `cloudwatch`,
  `cloudwatchlogs`, and `imds`.
- `bluetape4k-aws` source exposes the Spring Boot integration APIs the workshop
  should consume:
  - `io.bluetape4k.aws.spring.cloudwatch.CloudWatchOperations`
  - `io.bluetape4k.aws.spring.cloudwatch.CloudWatchLogsOperations`
  - `io.bluetape4k.aws.spring.cloudwatch.CloudWatchMeterPublishingOperations`
  - `io.bluetape4k.aws.spring.imds.ImdsOperations`
- `bluetape4k-aws` lessons explicitly warn that CloudWatch Micrometer support
  is a manual snapshot publisher, not a global `micrometer-registry-cloudwatch`
  replacement, and that IMDS must not be used as an automatic credential path.
- Current `aws/README.md` documents only local S3 examples, so AWS README,
  root README, and locale pairs must be updated.
- `Examples.yml` and `scripts/smoke-validate.sh` do not include a CloudWatch or
  IMDS example yet.

## Constraints

- The module is a consumer workshop, not a bluetape4k library. Use the root
  `bluetape4k-dependencies` BOM only.
- Default tests must not require AWS credentials, EC2 runtime, LocalStack, or
  real IMDS access.
- IMDS behavior must be explicit opt-in in docs and code. Do not model it as a
  credential provider or startup probe.
- The example should teach observability intent and boundaries with small,
  readable code rather than reproducing the `bluetape4k-aws` auto-configuration
  test suite.
- README work must be bilingual: `README.md` and `README.ko.md`.
- Diagram work must use `$bluetape4k-diagram`, include SVG and PNG assets, pass
  the current diagram checklist, and include full-size visual inspection.

## Goals

1. Add `aws/cloudwatch-imds-observability` as a runnable Spring Boot example.
2. Demonstrate custom metric publishing through `CloudWatchOperations`.
3. Demonstrate CloudWatch Logs event publishing through `CloudWatchLogsOperations`.
4. Demonstrate explicit EC2 metadata reads through `ImdsOperations`.
5. Record Micrometer timers/counters around operation outcomes and publish a
   selected meter snapshot through `CloudWatchMeterPublishingOperations`.
6. Keep default tests local by using stubbed or mocked operations, with no real
   AWS or IMDS network dependency.
7. Teach the local mode, optional real AWS mode, and credential/metadata
   boundary in bilingual README files and diagrams.
8. Register the new module in validation and example smoke coverage.

## Non-Goals

- Do not replace or refactor the existing S3 examples.
- Do not dispatch real CloudWatch or IMDS calls in default tests.
- Do not add `micrometer-registry-cloudwatch`.
- Do not implement a scheduler for periodic CloudWatch export.
- Do not expose IMDS security credential documents, temporary credential
  values, or an automatic credential strategy.
- Do not add Testcontainers or LocalStack unless implementation evidence proves
  it is needed. The expected design does not need containers.

## Approach Options

### Option A - Spring Boot Local-First Consumer Module

Create a Spring Boot MVC/Actuator example that consumes the published
`bluetape4k-aws` Spring interfaces through application services. Tests provide
local stub implementations of the operations and verify requests, tags, failure
behavior, and IMDS boundaries.

Benefits:

- Matches issue labels and the existing AWS/Spring Boot workshop shape.
- Keeps tests fast and credential-free.
- Teaches the consumer-facing contract instead of duplicating library tests.
- Makes README/diagram flow clear for learners.

Costs:

- Requires small local fake adapters for deterministic tests.
- Does not prove real AWS round-trips; docs must be explicit that real mode is
  optional.

### Option B - Ktor Example

Create a Ktor example using `bluetape4k-aws` Ktor plugins for CloudWatch, Logs,
and IMDS.

Benefits:

- Shows a newer Ktor-specific surface.
- Could align with the Ktor plugin work in `bluetape4k-aws`.

Costs:

- Issue is labeled `area:spring-boot`.
- Existing AWS workshop examples are Spring-focused.
- Would split reader attention between web framework mechanics and the
  observability/IMDS boundary.

### Option C - Real AWS Optional Integration Module

Build a module that can publish to real CloudWatch and query IMDS under an
opt-in profile.

Benefits:

- Demonstrates production wiring directly.
- Useful for manual verification on EC2.

Costs:

- Risky for CI and learners without AWS accounts.
- Harder to keep failure cases deterministic.
- Violates the issue's default-test boundary if not carefully isolated.

## Decision

Use Option A. The workshop module will be a Spring Boot local-first consumer
example. It will expose simple endpoints and services that make the intent
visible, but tests will call the service layer directly and through a light
Spring context with in-memory operation implementations.

The optional real AWS mode will be documented as a manual profile. It will not
run in CI and will not be required for the module's DoD.

## Architecture

### Runtime Components

- `ObservabilityApplication`: Spring Boot entrypoint.
- `OrderTelemetryController`: small HTTP facade for learner inspection.
- `OrderTelemetryService`: orchestrates simulated order processing, metrics,
  logs, and metadata lookups.
- `OrderTelemetryRequest` / `OrderTelemetryReport`: request and response DTOs.
- `TelemetryOutcome`: success/failure outcome model.
- `AwsObservabilityProperties`: local namespace/log group/log stream and IMDS
  opt-in settings used by the workshop.
- `LocalAwsObservabilityConfig`: local profile beans that implement
  `CloudWatchOperations`, `CloudWatchLogsOperations`, `ImdsOperations`, and a
  CloudWatch meter publisher without real AWS.
- `RealAwsObservabilityConfig`: optional profile boundary that relies on
  `bluetape4k-aws` auto-configuration when the user supplies AWS SDK service
  dependencies, region, and credentials.

### Data Flow

1. The user calls a local endpoint or service method for an order outcome.
2. The service records Micrometer timer/counter state.
3. The service builds a CloudWatch `MetricDatum` with stable dimensions such as
   `Outcome`, `Service`, and `Source`.
4. The service sends the metric through `CloudWatchOperations`.
5. The service builds a CloudWatch Logs `InputLogEvent` with sanitized event
   fields and sends it through `CloudWatchLogsOperations`.
6. If metadata lookup is explicitly requested, the service reads safe metadata
   through `ImdsOperations` helpers such as `instanceId`, `region`, and
   `availabilityZone`.
7. The service publishes a selected Micrometer meter snapshot through
   `CloudWatchMeterPublishingOperations`.
8. The report returns what would have been sent and whether metadata was read.

### Failure Handling

- CloudWatch metric publish failure returns a failed report and records a local
  failure counter.
- CloudWatch Logs publish failure returns a failed report and records a local
  failure counter.
- IMDS is skipped by default. When requested, metadata failures are captured as
  metadata status in the report without exposing credential documents.
- `CancellationException` is not swallowed around suspend calls.
- Caller input is validated with bluetape4k validation helpers where production
  code accepts direct caller values.

## Test Strategy

- TDD is mandatory for service behavior. Tests are written and verified failing
  before production implementation.
- Unit/service tests use MockK or deterministic fake operations:
  - metric datum name, namespace, dimensions, and value mapping
  - log group, stream, event timestamp/message mapping
  - Micrometer counter/timer increments for success and failure
  - CloudWatch publish failure behavior
  - CloudWatch Logs publish failure behavior
  - IMDS default skip behavior
  - explicit metadata opt-in reads only safe helper values
  - no read of `/latest/meta-data/iam/security-credentials/{role}` document
- Spring context smoke tests verify the local profile wires the service and
  local operation beans without credentials.
- No Testcontainers tests are planned. The module should be part of the
  no-container observability smoke path.

## Documentation and Diagrams

Create or update:

- `aws/cloudwatch-imds-observability/README.md`
- `aws/cloudwatch-imds-observability/README.ko.md`
- `aws/README.md`
- `aws/README.ko.md`
- root `README.md`
- root `README.ko.md`

Add diagrams under `docs/images/readme-diagrams/`:

- `aws-cloudwatch-imds-observability-readme-architecture-01.svg/png`
- `aws-cloudwatch-imds-observability-readme-sequence-01.svg/png`

Diagram requirements:

- Use official AWS CloudWatch and CloudWatch Logs icons from the shared wiki
  icon catalog when the card represents AWS managed services.
- Use layered architecture for the static component diagram.
- Use the current best-practices sequence family for the sequence diagram.
- Message labels must be numbered and visible.
- `alt` or `else` region bodies must be transparent, with branch-specific line
  colors for success, failure, and IMDS skip/read branches.
- Render SVG to PNG and inspect every touched PNG at full size.

## CI and Registration

- `settings.gradle.kts` should auto-register the new module through
  `includeModules("aws", false, true)`. Verify with `./gradlew projects`.
- Add path filters and smoke job coverage to `.github/workflows/Examples.yml`.
- Add `:aws-cloudwatch-imds-observability:test` to
  `scripts/smoke-validate.sh observability` or another no-container smoke group.
- Update stale-check expected project count.
- Run `actionlint` after workflow edits.

## Acceptance Criteria Mapping

| Issue criterion | Design response |
|---|---|
| Uses root BOM only | Keep `bluetape4k-aws` alias versionless; add AWS SDK aliases without bluetape4k version pins. |
| Adds/reuses version-catalog aliases | Add `aws2-cloudwatch-lib`, `aws2-cloudwatchlogs-lib`, and `aws2-imds-lib` aliases if the module needs SDK classes directly. |
| Tests metric/log publishing intent | Service tests capture `MetricDatum` and `InputLogEvent` values. |
| Tests tag/field mapping | Assertions cover metric dimensions and log event fields. |
| Tests failure behavior | Mocked operations throw and service returns failure reports while recording failure meters. |
| Tests explicit IMDS boundaries | Default path skips IMDS; opt-in path reads safe helpers only; credential document path is not called. |
| README documents local/optional real AWS/IMDS boundary | Module README locale pair includes local profile, optional real AWS profile, and credential warning. |
| CI/default smoke tests no real AWS | Local profile and fake operations are used in tests; no containers or real credentials. |

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Published `bluetape4k-aws` artifact differs from sibling source | Verify compile against this repo's resolved dependencies and use only public APIs. |
| Example duplicates `bluetape4k-aws` library tests | Keep focus on consumer orchestration and learner-facing behavior. |
| IMDS accidentally becomes credential guidance | Document that IMDS metadata is not an automatic credential strategy and avoid credential document endpoints. |
| Diagrams regress sequence best-practices | Start from current best-practices references and run sequence style audits plus full-size PNG inspection. |
| New module skipped by CI | Update workflow path filters, smoke task list, artifacts when needed, and `stale-check` project count. |
| Tests become slow or credential-sensitive | Use local fake operations and direct service tests; keep real AWS mode manual only. |

## Verification Plan

Local verification after implementation:

- `./gradlew :aws-cloudwatch-imds-observability:compileKotlin --warning-mode all --console=plain`
- `./gradlew :aws-cloudwatch-imds-observability:compileTestKotlin --warning-mode all --console=plain`
- `./gradlew :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain`
- `./gradlew projects --console=plain`
- `node scripts/validate-readme-language.mjs`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-readme-architecture-diagrams.mjs`
- `node scripts/validate-sequence-diagrams.mjs`
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-sequence-style-audit.py docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg`
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.svg docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg`
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.svg docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg`
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-connector-audit.py docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.svg docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg`
- `~/.local/bin/cairosvg <svg> -o <png> -s 2` for every touched SVG
- full-size visual inspection of every touched PNG
- `actionlint .github/workflows/Examples.yml`
- `./scripts/smoke-validate.sh stale-check`
- `git diff --check`

Review gates:

- Step 2-R spec review: P0=0, P1=0.
- Step 3-R plan review: P0=0, P1=0.
- Step 6-R implementation review: P0=0, P1=0.
- Step 7-R PR review: P0=0, P1=0.

## Open Questions

No blocking open question remains. The design intentionally chooses a local
Spring Boot consumer example and documents real AWS execution as manual opt-in.
