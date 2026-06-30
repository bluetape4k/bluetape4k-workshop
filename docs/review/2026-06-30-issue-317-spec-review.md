# Issue #317 Spec Review

- Date: 2026-06-30
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/317
- Spec: `docs/superpowers/specs/2026-06-30-issue-317-cloudwatch-imds-observability-design.md`
- Review gate: Step 2-R
- Execution note: Native subagent cleanup stalled in the previous turn, so this review records the same six-perspective contract in the main session to avoid blocking the workflow.

## Verdict

| Severity | Count | Status |
|---|---:|---|
| P0 | 0 | PASS |
| P1 | 0 | PASS |
| P2 | 6 | Carry into Step 3 plan |
| P3 | 3 | Optional polish |

Step 2-R may proceed because P0=0 and P1=0.

## Perspective Findings

### 1. Performance

Reviewed hot paths, latency, allocation, contention, and smoke-test cost.

- P0/P1: none.
- P2: Keep CloudWatch metric dimensions low-cardinality. Do not add order id,
  customer id, request id, or metadata values as dimensions.
- P2: Keep default tests local and no-container so the Examples workflow does
  not become slower or credential-sensitive.
- P3: In docs, mention that this example publishes explicit snapshots and is
  not a high-throughput exporter.

Evidence:

- The spec already chooses stable dimensions: `Outcome`, `Service`, and
  `Source`.
- The spec excludes `micrometer-registry-cloudwatch`, a scheduler, LocalStack,
  Testcontainers, and real AWS calls in default tests.

### 2. Stability

Reviewed cancellation, retry/failure behavior, local/real mode boundaries, and
test determinism.

- P0/P1: none.
- P2: The implementation plan must define partial-failure behavior when metric
  publish, logs publish, and optional metadata reads have different outcomes.
- P2: Tests must prove `CancellationException` is rethrown, not converted into
  a failure report.
- P3: If a manual real AWS profile is added, docs should recommend short SDK
  timeouts for experiments.

Evidence:

- The spec requires publish-failure reports, local failure counters, metadata
  failure capture, and explicit `CancellationException` handling.
- Default tests use deterministic fake or mocked operations.

### 3. Security

Reviewed sensitive data exposure, credential leakage, IMDS boundaries, and safe
defaults.

- P0/P1: none.
- P2: Log event construction must sanitize free-form request fields and avoid
  credentials, tokens, headers, environment values, or full exception stacks.
- P2: IMDS tests must assert the service never reads credential document paths,
  not only that the README warns against it.
- P3: README can briefly point learners to standard AWS SDK credential provider
  behavior without presenting IMDS as the workshop's credential mechanism.

Evidence:

- The spec states IMDS is opt-in, not a credential provider or startup probe.
- It explicitly excludes IMDS security credential documents and temporary
  credential values.

### 4. Operator

Reviewed CI registration, rollback, observability, namespace ownership, and
runbook clarity.

- P0/P1: none.
- P2: Step 3 plan must include exact `.github/workflows/Examples.yml` path
  filter/job edits and `scripts/smoke-validate.sh` stale-count update.
- P2: README must include local run commands, optional real AWS profile
  commands, required environment variables, and cleanup/cost warnings for real
  CloudWatch resources.

Evidence:

- The spec already calls out Examples workflow registration, observability smoke
  registration, `actionlint`, and `stale-check`.
- The optional real AWS mode is manual and outside CI.

### 5. Developer/API

Reviewed module shape, Kotlin/Spring conventions, dependency realism, and
maintainability.

- P0/P1: none.
- P2: The implementation plan must verify the exact public API signatures
  against resolved `bluetape4k-aws` artifacts, not only sibling source.
- P2: New DTO data classes must implement `java.io.Serializable` and define
  `serialVersionUID` to satisfy workspace Kotlin rules.
- P3: Keep the module package narrow, for example
  `io.bluetape4k.workshop.aws.observability`.

Evidence:

- The spec uses the root BOM and versionless `bluetape4k-aws` alias.
- `settings.gradle.kts` auto-registers the new AWS module path.

### 6. User/Caller

Reviewed learner ergonomics, misuse resistance, README flow, diagrams, and
unsupported capabilities.

- P0/P1: none.
- P2: README examples must show both successful and failed telemetry reports so
  learners understand local simulation and failure boundaries.
- P2: Diagrams must make local fake operations visually distinct from real AWS
  managed services and use official AWS icons only for managed services.
- P2: Sequence diagram must use numbered calls, transparent alt/else bodies,
  branch-specific line colors, and full-size PNG visual inspection evidence.

Evidence:

- The spec requires bilingual README files, layered architecture diagram,
  best-practices sequence diagram, official AWS CloudWatch icons, and full-size
  visual inspection.

## Integrated Review

No P0 or P1 issue blocks the design. The design matches issue #317, the
milestone scope, the repository module-registration model, and the documented
`bluetape4k-aws` boundaries:

- CloudWatch and CloudWatch Logs are demonstrated through public Spring-facing
  operations.
- Micrometer support is a manual snapshot publisher, not a registry replacement
  or scheduled exporter.
- IMDS is explicitly opt-in and not a credential strategy.
- Default tests and CI remain local, fast, and credential-free.

Required Step 3 plan carry-forward items:

1. Define partial-failure semantics for metric publish, logs publish, meter
   snapshot publish, and optional IMDS reads.
2. Add tests for `CancellationException` propagation.
3. Assert no IMDS credential document path is read.
4. Verify public API signatures against the resolved dependency artifact.
5. Include exact Examples workflow and smoke-validation edits.
6. Ensure diagrams distinguish local fake beans from real AWS managed services
   and pass all sequence/style/geometry/endpoint/connector audits plus visual
   inspection.

## Rejected Items

- Do not add a real AWS integration test to prove the example. It would violate
  the issue's credential-free default-test boundary.
- Do not add `micrometer-registry-cloudwatch`. The workshop target is the
  published `CloudWatchMeterPublishingOperations` snapshot publisher.
- Do not model IMDS as automatic credential discovery. The workshop should
  present IMDS only as explicit safe metadata reads.

## Open Questions

None.
