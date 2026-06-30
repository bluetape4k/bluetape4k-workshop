# Issue #317 Plan Review

- Date: 2026-06-30
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/317
- Plan: `docs/superpowers/plans/2026-06-30-issue-317-cloudwatch-imds-observability-plan.md`
- Spec: `docs/superpowers/specs/2026-06-30-issue-317-cloudwatch-imds-observability-design.md`
- Spec review: `docs/review/2026-06-30-issue-317-spec-review.md`
- Review gate: Step 3-R
- Execution note: Native subagent management was unreliable in this session, so this review records the same six-perspective contract in the main session.

## Verdict

| Severity | Count | Status |
|---|---:|---|
| P0 | 0 | PASS |
| P1 | 0 | PASS |
| P2 | 9 | Carry into implementation checklist |
| P3 | 3 | Optional polish |

Step 3-R may proceed because P0=0 and P1=0.

## Perspective Findings

### 1. Performance

- P0/P1: none.
- P2: During implementation, reject any accidental high-cardinality CloudWatch
  dimensions in review. The plan correctly limits dimensions to `Outcome`,
  `Service`, and `Source`.
- P3: README should call out that the example is an explicit snapshot publisher,
  not a high-throughput telemetry pipeline.

Evidence:

- Plan Task 5 restricts CloudWatch dimensions to low-cardinality values.
- Plan Tasks 2, 7, and 8 keep default verification no-container and
  credential-free.

### 2. Stability

- P0/P1: none.
- P2: Implementation must define deterministic ordering in partial-failure
  reports so tests do not become brittle.
- P2: Cancellation tests must cover at least one publish path that throws
  `CancellationException`.

Evidence:

- Plan Task 2 requires mixed partial-failure tests and cancellation propagation
  tests before production code.
- Plan Task 3 defines independent metric/log/meter/metadata failure semantics.

### 3. Security

- P0/P1: none.
- P2: Review generated JSON log examples to ensure they do not teach learners to
  include secrets, headers, environment values, raw metadata documents, or
  credential paths.

Evidence:

- Plan Tasks 2 and 5 assert safe log fields and absence of sensitive request
  data.
- Plan Tasks 2 and 6 assert no IMDS credential document reads and document the
  credential boundary.

### 4. Operator

- P0/P1: none.
- P2: If optional real AWS profile documentation includes resource creation,
  it must include cleanup/cost guidance in both locales.
- P3: If actionlint is not installed locally, record the installation gap and
  rely on GitHub Actions validation after PR creation.

Evidence:

- Plan Task 6 includes local run commands, optional real AWS commands,
  environment variables, and cost/cleanup warnings.
- Plan Task 7 includes exact Examples workflow, artifact, smoke group, and
  stale-count edits.

### 5. Developer/API

- P0/P1: none.
- P2: Confirm the `springBoot.mainClass` value during Task 1 because existing
  Spring Boot examples set it explicitly.
- P2: Keep optional real profile classes out of the critical path if the
  resolved `bluetape4k-aws` auto-configuration already supplies real clients.

Evidence:

- Plan includes resolved artifact API verification before implementation.
- Plan includes serializable DTO rules, validation helpers, and no local
  bluetape4k version pinning.

### 6. User/Caller

- P0/P1: none.
- P2: README examples must include at least one failed report and one metadata
  skipped report, not only happy path output.
- P2: Diagram review must verify official AWS icons only on real managed
  services and distinct styling for local fake beans.

Evidence:

- Plan Task 6 requires bilingual README source-equivalence, successful and
  failed report examples, metadata/credential boundaries, official icons,
  transparent sequence regions, branch-colored calls, and full-size PNG
  inspection.

## Integrated Review

The plan maps the spec and issue acceptance criteria to concrete tasks with
implementable ordering:

1. Dependency/API guard and module skeleton come before code.
2. Red tests precede production implementation.
3. Domain, local beans, service, HTTP boundary, docs, diagrams, CI, and final
   verification are sequenced without forward dependencies.
4. The Step 2-R P2 items are all represented in named tasks.
5. README locale pairs, diagram audits, visual inspection, CI registration, PR
   metadata, and final `## DoD Status` evidence are covered.

No P0/P1 finding blocks implementation.

## Required Implementation Checklist Additions

These do not require another plan edit because the plan already covers them,
but they must be checked during Step 4-6:

1. `springBoot.mainClass` is explicit.
2. Partial-failure report ordering is deterministic.
3. Cancellation test uses a real `CancellationException`.
4. README examples include failed and metadata-skipped reports.
5. Optional real AWS docs include cleanup/cost guidance in both locales.
6. Diagram visual inspection checks icon source, layer distinction, sequence
   numbering, transparent branch bodies, and branch line colors.

## Rejected Items

- Do not move the module into the Spring Boot directory. The issue is AWS
  observability and `settings.gradle.kts` already auto-registers AWS modules.
- Do not add LocalStack or Testcontainers for this example. They would obscure
  the learner-facing CloudWatch/IMDS boundary and slow default smoke coverage.
- Do not delay docs and diagrams until after PR review. They are core
  deliverables for workshop examples.

## Open Questions

None.
