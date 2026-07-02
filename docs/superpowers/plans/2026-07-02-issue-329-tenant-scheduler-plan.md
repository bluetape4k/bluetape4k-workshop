# Tenant-Scoped Leader Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development or superpowers:executing-plans to
> implement this plan task-by-task. Keep each checkbox current as work lands.

**Goal:** Build `leader/tenant-scheduler`, a deterministic workshop module that
teaches tenant-scoped leader scheduling with lock names derived from
`TenantLockNamespace`.

**Architecture:** The module is a finite logical-tick lab. It models tenant-local
leases, per-tenant execution/failure/stale-handoff outcomes, and bounded metric
tag guidance without starting Redis, ZooKeeper, Kubernetes, PostgreSQL,
Testcontainers, background schedulers, real timers, Awaitility, or polling loops.

**Tech Stack:** Kotlin plugin/catalog 2.4.0 with root `languageVersion` and
`apiVersion` set to Kotlin 2.3, Java 21, Spring Boot 4 module shape, root
`bluetape4k-dependencies` BOM only, `bluetape4k-core`, `bluetape4k-leader-core`,
`bluetape4k-logging`, JUnit 5, `bluetape4k-assertions`, bilingual README pages,
generated SVG+PNG README diagrams, GitHub Actions smoke validation.

Do not use Kotlin 2.4-only language or API features in this module.

**Spec:** `docs/superpowers/specs/2026-07-02-issue-329-tenant-scheduler-design.md`

## File Structure

- Create `leader/tenant-scheduler/build.gradle.kts`.
- Create `leader/tenant-scheduler/README.md`.
- Create `leader/tenant-scheduler/README.ko.md`.
- Create `leader/tenant-scheduler/src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/TenantSchedulerLabApp.kt`.
- Create domain files:
  - `domain/TenantId.kt`
  - `domain/TenantJobName.kt`
  - `domain/TenantNodeId.kt`
  - `domain/TenantLogicalTick.kt`
  - `domain/TenantLeaseWindow.kt`
  - `domain/TenantSchedulePolicy.kt`
  - `domain/TenantScheduleTick.kt`
  - `domain/TenantLeaseState.kt`
  - `domain/TenantRunOutcome.kt`
  - `domain/TenantSchedulerReport.kt`
- Create service files:
  - `service/TenantLockNamePlanner.kt`
  - `service/TenantMetricTagPolicy.kt`
  - `service/TenantSchedulerLab.kt`
- Create tests:
  - `service/TenantIdentifierValidationTest.kt`
  - `service/TenantLockNamePlannerTest.kt`
  - `service/TenantMetricTagPolicyTest.kt`
  - `service/TenantSchedulerLabTest.kt`
- Create `src/main/resources/application.yml`.
- Create `src/test/resources/junit-platform.properties`.
- Create `src/test/resources/logback-test.xml`.
- Create diagrams:
  - `docs/images/readme-diagrams/leader-tenant-scheduler-readme-architecture-01.svg`
  - `docs/images/readme-diagrams/leader-tenant-scheduler-readme-architecture-01.png`
  - `docs/images/readme-diagrams/leader-tenant-scheduler-readme-sequence-01.svg`
  - `docs/images/readme-diagrams/leader-tenant-scheduler-readme-sequence-01.png`
- Modify root `README.md` and `README.ko.md`.
- Modify `scripts/smoke-validate.sh`.
- Modify `.github/workflows/Examples.yml`.
- Modify `.github/workflows/nightly.yml` only if workflow scan shows the new
  module is not covered by the existing all-smoke path.
- Create `docs/review/2026-07-02-issue-329-tenant-scheduler-code-review.md`.
- Create `docs/lessons/2026-07-02-issue-329-tenant-scheduler.md`.

## Task 0: Commit Reviewed Spec And Plan

**Complexity:** low

**Applies:** `$bluetape4k-full-feature`

- [ ] Run plan draft-marker and consistency checks.
- [ ] Run Step 3-R plan review and apply any P0/P1 findings.
- [ ] Commit the reviewed spec and plan before implementation starts.

Verification:

```bash
rg -n "TB[D]|TO[D]O|FIXM[E]|\\?\\?|mayb[e]" \
  docs/superpowers/specs/2026-07-02-issue-329-tenant-scheduler-design.md \
  docs/superpowers/plans/2026-07-02-issue-329-tenant-scheduler-plan.md
git diff --check
```

## Task 1: Build Skeleton And RED Validation Tests

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] Add `leader/tenant-scheduler/build.gradle.kts` with versionless
  dependencies only.
- [ ] Mirror the nearby leader Spring Boot module shape:
  `kotlin.spring`, `spring.boot`, `springBoot.mainClass`, annotation processors,
  devtools runtime, and Spring Boot starter test exclusions for JUnit vintage and
  Mockito.
- [ ] Add test resources with JUnit parallel execution disabled and
  per-class lifecycle.
- [ ] Add RED tests for `TenantId`, `TenantJobName`, and `TenantNodeId`.
- [ ] Prove the tests fail because production types do not exist yet.

Implementation constraints:

- Use only the root BOM and catalog aliases.
- Production dependencies stay limited to deterministic lab needs:
  `bluetape4k-core`, `bluetape4k-leader-core`, `bluetape4k-logging`, Spring Boot
  autoconfigure/actuator for the runnable workshop shape.
- Test dependencies: `project(":shared")`, `bluetape4k-junit5`,
  `bluetape4k-assertions`, Spring Boot starter test.
- Do not add Awaitility, Testcontainers, Redis, ZooKeeper, Kubernetes, database,
  or backend client dependencies.

RED command:

```bash
./gradlew :leader-tenant-scheduler:test \
  --tests '*TenantIdentifierValidationTest' \
  --no-build-cache --rerun-tasks
```

Expected: FAIL because identifier value objects do not exist.

## Task 2: Implement Identifier Value Objects

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`

- [ ] Implement `TenantId`, `TenantJobName`, and `TenantNodeId` as immutable
  serializable domain values.
- [ ] Use private constructors plus companion `operator fun invoke(...)`
  factories; expose canonical lowercase `value`.
- [ ] Add `@ConsistentCopyVisibility` where data classes need private
  constructors.
- [ ] Add `serialVersionUID` to every serializable data class.
- [ ] Add English KDoc to public value types and factories.
- [ ] Canonicalize accepted input to lowercase.
- [ ] Accept only `[a-z][a-z0-9-]*[a-z0-9]` with length `3..64`.
- [ ] Reject blanks, `:`, `_`, `.`, `/`, whitespace, control characters,
  newlines, email-like values, raw account identifiers, and over-length values.
- [ ] Reject account-id-shaped aliases, including patterns such as
  `acct-123456789012`, `aws-123456789012`, and `customer-123456`.
- [ ] Assert validation exception messages do not contain the raw rejected input;
  messages name only the field and failed rule.
- [ ] Use bluetape4k validation helpers and preserve `IllegalArgumentException`
  for caller input failures.

Verification:

```bash
./gradlew :leader-tenant-scheduler:test \
  --tests '*TenantIdentifierValidationTest' \
  --no-build-cache --rerun-tasks
```

## Task 3: Implement Lock Planner And Metric Tag Policy

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`

- [ ] Add RED tests for `TenantLockNamePlanner`.
- [ ] Add RED tests for `TenantMetricTagPolicy`.
- [ ] Run focused tests and record expected RED failure before production
  implementation.
- [ ] Implement `TenantLockNamePlanner` using
  `TenantLockNamespace(tenant.value).lockName(jobName.value)`.
- [ ] Prove the generated backend lock name is
  `tenant:tenant-a:invoice-sync`.
- [ ] Expose learner-visible rows that distinguish backend lock identity from
  metric dimensions.
- [ ] Implement safe metric tag guidance:
  - `DEFAULT_MAX_TENANT_TAG_VALUES = 16`;
  - `MAX_LOCAL_TENANT_TAG_VALUES = 100`;
  - if `tenantCount <= maxTenantTagValues` and the requested limit is within the
    hard local cap, emit per-tenant tags;
  - if `tenantCount > maxTenantTagValues` or the requested limit exceeds the hard
    local cap, emit `tenant=bounded` and `cardinalityLimited=true`;
  - never include raw PII, backend lock names, job names, node ids, or unbounded
    tenant values as metric dimensions.
- [ ] Add a high-cardinality test, for example `tenantCount=10_000`, proving
  emitted metric rows stay bounded even when a caller requests a huge tag limit.
- [ ] Add allowed-tag tests that forbid `lockName`, backend lock strings, raw job
  names, and node ids as metric dimensions.

Verification:

```bash
./gradlew :leader-tenant-scheduler:test \
  --tests '*TenantLockNamePlannerTest' \
  --tests '*TenantMetricTagPolicyTest' \
  --no-build-cache --rerun-tasks
```

## Task 4: Implement Deterministic Scheduler Lab

**Complexity:** high

**Applies:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] Add RED tests for policy validation, tick validation, and deterministic
  reports.
- [ ] Run focused tests and record expected RED failure before production
  implementation.
- [ ] Implement immutable domain values for policies, ticks, lease state,
  outcomes, event rows, and reports.
- [ ] Introduce `TenantLogicalTick` and
  `TenantLeaseWindow(acquiredAt, renewedAt, expiresAt)` so public constructors do
  not expose multiple same-typed tick parameters.
- [ ] Add `serialVersionUID` to every serializable data class.
- [ ] Implement `TenantSchedulerLab.run(policy, ticks)` as a pure reducer.
- [ ] Preserve independent tenant state: a tenant failure never aborts later
  tenant evaluation in the same tick.
- [ ] Preserve lease semantics:
  - active while `currentTick < expiresAtTick`;
  - stale handoff allowed when `currentTick >= expiresAtTick`;
  - failed tenant actions keep their lease until the same stale boundary;
  - other tenant leases are not modified.
- [ ] Implement exact lease transition rows:
  - no lease plus successful first candidate acquires and executes;
  - no lease plus failed first candidate acquires, records `failed`, and keeps
    the new expiry;
  - active owner present plus success renews and extends expiry;
  - active owner present plus failure keeps acquired/renewed/expiry unchanged;
  - active owner absent before expiry records `skipped` and does not hand off;
  - non-owner candidates before expiry record `skipped` and do not mutate state;
  - expired lease plus successful first candidate records `stale-handoff`;
  - expired lease plus failed first candidate replaces owner for the failed
    action and keeps the new expiry.
- [ ] Implement bounded-capacity fairness by `lastSelectedTick` ascending, then
  tenant alias.
- [ ] Initialize never-selected tenants with `TenantLogicalTick.MIN`.
- [ ] Update `lastSelectedTick` for every selected tenant outcome, including
  `executed`, `failed`, `skipped`, and `stale-handoff`; do not update unselected
  tenants.
- [ ] Keep report rows bounded with `DEFAULT_EVENT_HISTORY_LIMIT = 64` and
  `MAX_EVENT_HISTORY_LIMIT = 512`.
- [ ] When rows exceed the configured limit, set `truncated=true`, increment
  `droppedEventRows`, and keep `eventRows.size <= eventHistoryLimit`.
- [ ] Ensure repeated calls and separate lab instances do not share mutable
  state.

Required tests:

- [ ] Two tenants run independently in one tick.
- [ ] One tenant failure does not block another tenant.
- [ ] Stale lease cannot hand off before expiry.
- [ ] Stale lease hands off exactly at the expiry boundary.
- [ ] Failed action retains the tenant lease until expiry.
- [ ] Bounded capacity chooses least-recently-run tenants deterministically.
- [ ] Capacity=1 across at least three tenants rotates over repeated ticks and
  proves no starvation under the selected sentinel/update rules.
- [ ] Same input produces the same report.
- [ ] Separate runs do not leak state.
- [ ] Stress-style logical tick scenario remains bounded and deterministic.
- [ ] Stress-style scenario asserts `eventRows.size <= eventHistoryLimit`,
  truncation fields, bounded metric rows, and no starvation over many tenants,
  nodes, and ticks.
- [ ] Duplicate-after-canonicalization tests cover duplicate tenants, due
  tenants, candidate nodes, initial leases, and failure entries.
- [ ] Source scan confirms no Awaitility, `Thread.sleep`, coroutine `delay`,
  `GlobalScope`, hidden scheduler/clock APIs, polling loop, Testcontainers, or
  backend client use.

Verification:

```bash
./gradlew :leader-tenant-scheduler:test --no-build-cache --rerun-tasks
if rg -n "awaitility|Awaitility|Thread\\.sleep|delay\\(|GlobalScope|@Scheduled|ScheduledExecutorService|Executors\\.|scheduleAtFixedRate|Timer\\(|System\\.currentTimeMillis|Instant\\.now|Clock\\.system|CoroutineScope\\(|launch\\(|async\\(|Testcontainers|GenericContainer|Redis|ZooKeeper|Kubernetes|PostgreSQL" \
  leader/tenant-scheduler/src; then exit 1; fi
if rg -n "testcontainers|awaitility|redis|zookeeper|kubernetes|postgres|bom\\(|version\\(" \
  leader/tenant-scheduler/build.gradle.kts; then exit 1; fi
```

The forbidden scans should return no forbidden runtime/test/build usage.

## Task 5: Add Learner README Pages

**Complexity:** medium

**Applies:** `$bluetape4k-blog`, `$bluetape4k-diagram`

- [ ] Add English `leader/tenant-scheduler/README.md`.
- [ ] Add source-equivalent Korean `leader/tenant-scheduler/README.ko.md`.
- [ ] Add language switch directly under each title.
- [ ] Explain why one global scheduled-job lock blocks tenant progress.
- [ ] Explain lock naming with `TenantLockNamespace` and show the generated
  `tenant:tenant-a:invoice-sync` form.
- [ ] Explain metric cardinality limits with safe/unsafe examples.
- [ ] Explain operational limits: stale leases, retry/deadline semantics,
  failure isolation, no PII in tags/logs/locks, and when to move to real
  backend modules.
- [ ] Include tested Kotlin snippets copied from
  `TenantSchedulerReadmeSnippetTest` or an equivalent test fixture.
- [ ] Add runbook sections for reset/rerun, cardinality warning interpretation,
  failed action interpretation, stale-lease interpretation, and next action
  before mapping to a real backend.
- [ ] Use redacted tokens only for unsafe examples, such as
  `<email-redacted>` and `acct-<redacted>`.
- [ ] Link to real practice paths:
  - `leader/leader-election`
  - `leader/leader-zookeeper`
  - `leader/k8s-lease-micrometer`
  - `leader/backend-comparison-lab`
  - `spring-boot/multi-tenant-data-isolation`
  - `bluetape4k-leader/examples/tenant-aggregator`

Verification:

```bash
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
./gradlew :leader-tenant-scheduler:test --tests '*TenantSchedulerReadmeSnippetTest'
if rg -n "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\b(?:acct|aws|customer)-[0-9]{6,}\\b" \
  leader/tenant-scheduler/README.md leader/tenant-scheduler/README.ko.md \
  leader/tenant-scheduler/src docs/images/readme-diagrams; then exit 1; fi
```

## Task 6: Generate And Audit README Diagrams

**Complexity:** high

**Applies:** `$bluetape4k-diagram`

- [ ] Inspect best-practice architecture and sequence references at full size
  before drawing.
- [ ] Create architecture diagram with clear layer boundaries and simple
  top-to-bottom flow.
- [ ] Include a visible production boundary, for example "Lab model: no
  distributed lock" versus "Production backends:
  Redis/ZooKeeper/Kubernetes/tenant-aggregator".
- [ ] Create sequence diagram in best-practices style:
  - numbered call labels;
  - labels do not cover call lines;
  - arrowhead color matches call-line color;
  - alt/else areas are transparent;
  - rounded-corner orthogonal connectors;
  - centered card text;
  - muted best-practice palette.
- [ ] Export SVG and PNG from the same source.
- [ ] Run the full diagram checklist.
- [ ] Perform visual inspection on full-size rendered PNGs.
- [ ] Fix every checklist or visual finding before embedding diagrams.

Verification:

```bash
./scripts/smoke-validate.sh diagram-qa
```

Required visual evidence:

- Full-size PNG inspection confirms no broken icons/images.
- SVG and PNG arrow directions match.
- Connectors are short, orthogonal, rounded where bent, and do not obscure text.
- Cards use consistent text alignment and layer styling.

## Task 7: Register Module In Repo And CI

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`

- [ ] Confirm `settings.gradle.kts` auto-registers
  `:leader-tenant-scheduler`.
- [ ] Add the new module to root `README.md` and `README.ko.md`.
- [ ] Update `scripts/smoke-validate.sh` expected module count and all-smoke
  test list.
- [ ] Update `.github/workflows/Examples.yml` path filters, smoke test command,
  and test-result artifacts.
- [ ] Scan `.github/workflows/nightly.yml` and update it only if the new module
  is not covered through all-smoke; if nightly invokes `all-smoke`, record that
  no module-specific nightly edit is needed.
- [ ] Run project listing, smoke command presence, all-smoke, and stale
  registration checks.
- [ ] Treat stale-check warnings about project count or registration drift as
  failures to repair before PR.

Verification:

```bash
./gradlew projects --console=plain
rg -n ":leader-tenant-scheduler:test" scripts/smoke-validate.sh .github/workflows/Examples.yml
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml
```

## Task 8: Final Verification, Review, And PR

**Complexity:** high

**Applies:** `$bluetape4k-workflow`, `$bluetape4k-full-feature`,
`$bluetape4k-code-patterns`, `$bluetape4k-diagram`

- [ ] Run targeted module tests.
- [ ] Run compile checks with warning mode.
- [ ] Run README snippet test.
- [ ] Run README parity/language checks.
- [ ] Run diagram QA and visual inspection.
- [ ] Run smoke command presence, all-smoke, and stale registration checks.
- [ ] Run workflow lint where changed.
- [ ] Run forbidden dependency/runtime/PII scans.
- [ ] Run `git diff --check`.
- [ ] Create `docs/review/2026-07-02-issue-329-tenant-scheduler-code-review.md`
  with 7-Tier review evidence.
- [ ] Create `docs/lessons/2026-07-02-issue-329-tenant-scheduler.md`.
- [ ] Commit with Lore trailers.
- [ ] Open a PR for issue #329, copying milestone, labels, and assignee.
- [ ] Verify live PR body, milestone, labels, assignee, checks, and issue link.

Verification command set:

```bash
./gradlew :leader-tenant-scheduler:test --no-build-cache --rerun-tasks
./gradlew :leader-tenant-scheduler:test --tests '*TenantSchedulerReadmeSnippetTest'
./gradlew :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --warning-mode all
./gradlew projects --console=plain
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
rg -n ":leader-tenant-scheduler:test" scripts/smoke-validate.sh .github/workflows/Examples.yml
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh diagram-qa
if rg -n "testcontainers|awaitility|redis|zookeeper|kubernetes|postgres|bom\\(|version\\(" leader/tenant-scheduler/build.gradle.kts; then exit 1; fi
if rg -n "Thread\\.sleep|delay\\(|GlobalScope|@Scheduled|ScheduledExecutorService|Executors\\.|scheduleAtFixedRate|Timer\\(|System\\.currentTimeMillis|Instant\\.now|Clock\\.system|CoroutineScope\\(|launch\\(|async\\(" leader/tenant-scheduler/src; then exit 1; fi
if rg -n "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\b(?:acct|aws|customer)-[0-9]{6,}\\b" leader/tenant-scheduler README.md README.ko.md docs/images/readme-diagrams; then exit 1; fi
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml
git diff --check
```

## Acceptance Mapping

| Issue requirement | Plan coverage |
|-------------------|---------------|
| Tenant/shard-based leader lock names | Tenant-scoped lock names via `TenantLockNamespace`; shard abstraction explicitly out of scope for #329. |
| Independent scheduled jobs | Tasks 4 and 8 prove two tenants progress independently. |
| Fairness | Task 4 bounded-capacity ordering by `lastSelectedTick`, then tenant alias. |
| Stale-lock handling | Task 4 active/expiry/handoff tests. |
| Per-tenant metrics/tags | Task 3 bounded metric tag policy and README guidance. |
| Default tests local and deterministic | Tasks 1-4 forbid timers, polling, Awaitility, Testcontainers, and backends. |
| Source-equivalent bilingual README | Task 5 parity/language checks and matching content requirements. |
| Tested README snippets | Task 5 requires snippets copied from `TenantSchedulerReadmeSnippetTest`. |
| Lab vs production distributed lock boundary | Tasks 5 and 6 require explicit README and diagram boundary labels. |
| Diagrams understandable and checklist-passing | Task 6 full diagram QA and visual inspection. |
| Repo registration | Task 7 projects, smoke presence, all-smoke, workflow, and stale checks. |

## Risks And Controls

| Risk | Control |
|------|---------|
| Example is mistaken for a production distributed lock | README and KDoc state that the lab models observable scheduling behavior only; real backend modules are linked. |
| Metric tags teach high-cardinality tenant IDs | Identifier grammar and metric policy suppress unbounded per-tenant rows. |
| Tenant failure accidentally aborts whole tick | Reducer tests assert later tenant evaluation after failure. |
| Stale handoff boundary is ambiguous | Tests pin `currentTick < expiresAtTick` active and `currentTick >= expiresAtTick` handoff. |
| Diagram passes script but fails visual quality | Full-size rendered PNG inspection is required before embedding. |
| New module missing from CI | Task 7 covers project listing, smoke script, Examples workflow, and stale-check. |
