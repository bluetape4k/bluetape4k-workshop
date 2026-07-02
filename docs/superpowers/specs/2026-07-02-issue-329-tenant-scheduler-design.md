# Issue #329 - Tenant-Scoped Leader Scheduler Design

**Date**: 2026-07-02
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/329
**Milestone**: 1.3.1
**Status**: Ready for implementation planning

## Goal

Add a `leader/tenant-scheduler` workshop module that teaches tenant-scoped
leader scheduling.

The module should show why one global scheduled-job lock is not enough for
multi-tenant systems: tenant A should be able to progress even when tenant B
fails, loses leadership, or waits for a stale lease to expire. Learners should
see how lock names, scheduler state, fairness, stale-lock handoff, and metric
tags interact without needing Redis, ZooKeeper, Kubernetes, PostgreSQL, or a
cloud account in the default test path.

Scope decision: #329 is implemented as a tenant-scoped module. Shard-scoped
scheduling follows the same lock-name pattern, but a separate shard abstraction,
shard key format, and shard-specific tests are out of scope for this example.

## Source Evidence

| Source | Evidence |
|--------|----------|
| GitHub issue #329 | Requires tenant/shard-based leader lock names, independent scheduled jobs, fairness, stale-lock handling, per-tenant/shard metrics/tags, deterministic local tests, and README locale parity. |
| `bluetape4k-leader/leader-core/TenantLockNamespace.kt` | Official tenant namespace API returns `prefix:tenantId:lockName`, rejects blank values and `:`, validates final lock names, and preserves an injective namespace mapping. |
| `bluetape4k-leader/leader-core/TenantScopedLeaderElectors.kt` | `forTenant(...)` wrappers translate caller-facing lock names for blocking, coroutine, group, and virtual-thread leader elector APIs. |
| `bluetape4k-leader/examples/tenant-aggregator` | Real backend-oriented tenant aggregation example uses independent lock names, long-running coroutine workers, exception isolation, and graceful stop. Workshop #329 should complement it rather than duplicate its R2DBC/runtime behavior. |
| `spring-boot/multi-tenant-data-isolation` | Existing workshop module already teaches tenant-scoped repository/cache/lock/rate-limit/metrics keys with local deterministic state. #329 should connect that key-design idea to leader scheduling. |
| `leader/backend-comparison-lab` | Recent leader workshop pattern uses deterministic local reports and source-backed README diagrams to teach operational leader behavior without replacing real backend practice modules. |
| Repo-local `AGENTS.md` | Workshop modules are consumer projects; use the root `bluetape4k-dependencies` BOM only and update smoke/workflow/stale-check registration when adding modules. |

CodeGraph note: the current `bluetape4k-workshop` worktree graph is empty, so
repo-local structural lookup fell back to direct source reads. Cross-repo
CodeGraph confirmed `TenantLockNamespace` as a `bluetape4k-leader` API.

## Brainstorming Summary

### Approach A - Full runtime scheduler using real leader backend

Create a runnable scheduler around Redis, ZooKeeper, or Kubernetes Lease and
prove tenant independence with the real backend.

**Rejected**: This would duplicate `bluetape4k-leader/examples/tenant-aggregator`
and make the default workshop test path backend-heavy. Issue #329 explicitly
requires local deterministic tests.

### Approach B - Documentation-only tenant scheduling guide

Add README pages and diagrams that explain tenant lock naming, fairness, and
metric cardinality without executable code.

**Rejected**: This would explain the pattern but would not prove that two
tenants coordinate independently or that one tenant failure does not block
another.

### Approach C - Deterministic tenant scheduler lab

Create a small deterministic module with:

- tenant-scoped lock-name planning based on `TenantLockNamespace`;
- immutable scheduler input policies;
- a local scheduler simulator that records per-tenant leader execution, skip,
  failure, stale-lease handoff, and fairness reports;
- metric-tag guidance that distinguishes safe bounded tenant tags from
  high-cardinality production risks;
- bilingual README pages and source-backed diagrams.

**Selected**: This satisfies #329 while preserving local deterministic tests and
maximizing reuse of existing bluetape4k leader APIs.

## Design

### Module

```text
leader/tenant-scheduler/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/
    TenantSchedulerLabApp.kt
    domain/TenantId.kt
    domain/TenantJobName.kt
    domain/TenantNodeId.kt
    domain/TenantLogicalTick.kt
    domain/TenantLeaseWindow.kt
    domain/TenantSchedulePolicy.kt
    domain/TenantScheduleTick.kt
    domain/TenantLeaseState.kt
    domain/TenantRunOutcome.kt
    domain/TenantSchedulerReport.kt
    service/TenantLockNamePlanner.kt
    service/TenantMetricTagPolicy.kt
    service/TenantSchedulerLab.kt
  src/main/resources/application.yml
  src/test/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/
    service/TenantLockNamePlannerTest.kt
    service/TenantMetricTagPolicyTest.kt
    service/TenantSchedulerLabTest.kt
  src/test/resources/junit-platform.properties
  src/test/resources/logback-test.xml
```

The Gradle project is auto-registered by `includeModules("leader", false, true)`
as `:leader-tenant-scheduler`.

### Core Model

`TenantId` is a small serializable value object that validates caller input with
bluetape4k validation helpers. It keeps tenant identifiers readable in tests and
README snippets.

The workshop uses only synthetic, non-sensitive tenant aliases such as
`tenant-a` and `tenant-b`. `TenantId` canonicalizes input to lowercase and
accepts only metric/log-safe aliases:

- length: 3-64 characters;
- grammar: `[a-z][a-z0-9-]*[a-z0-9]`;
- rejected: blanks, `:`, `_`, `.`, `/`, whitespace, control characters,
  newlines, email-like values, raw account identifiers, and values too long for
  readable locks/tags.

Production readers must map customer names, emails, account ids, or other PII to
stable non-sensitive tenant aliases before using them in lock names, metric
tags, logs, reports, or diagrams.

`TenantSchedulePolicy` defines:

- tenant-local job name, such as `invoice-sync`;
- selected tenants;
- per-tick scheduling capacity, defaulting to the tenant count so every due
  tenant can progress independently in the normal lab scenario;
- maximum metric tag cardinality allowed in the local lab;
- stale lease threshold in logical ticks.
- event history limit for the readable report.

Job names use the same lowercase metric/log-safe grammar as tenant aliases and
are intentionally tenant-local. The backend lock name comes from
`TenantLockNamespace(tenantAlias).lockName(jobName)`, not from manual string
concatenation.

Policy validation fails fast with `IllegalArgumentException` for empty tenants,
duplicate tenants after canonicalization, non-positive capacity, capacity larger
than tenant count, non-positive stale threshold, non-positive metric tag limit,
non-positive report history limit, or report history limits above
`MAX_EVENT_HISTORY_LIMIT`. Excessive metric cardinality or a requested metric tag
limit above `MAX_LOCAL_TENANT_TAG_VALUES` is not a construction failure; it is a
reportable safe-degradation path handled by `TenantMetricTagPolicy`.

Remove any separate `fairnessWindow` API unless implementation evidence shows a
real reader need; the selected deterministic fairness contract is already
`lastSelectedTick` ascending, then tenant alias.

### API And Validation Contract

The module should use named immutable value objects instead of same-typed raw
string parameters:

- `TenantId` for non-sensitive tenant aliases;
- `TenantJobName` for tenant-local job names;
- `TenantNodeId` for synthetic scheduler node aliases.

Domain values should be immutable, serializable data classes or value classes
where Kotlin allows the required validation. Constructors or companion factories
must use bluetape4k validation helpers for caller input and preserve
`IllegalArgumentException` for invalid caller values. Collections exposed from
policies and reports should be immutable snapshots.

Serializable data classes must define `private const val serialVersionUID: Long`
through a companion object. If data classes need validation, use private
constructors plus companion factories and `@ConsistentCopyVisibility` where
needed so generated `copy` cannot bypass validation.

Validation exception messages must not echo raw rejected input. Messages name the
field and failed rule only, so emails, account-id-like values, control-character
samples, and other unsafe raw input do not appear in logs or test output.

Numeric policy values have explicit constraints:

- `maxTenantsPerTick` in `1..tenantCount`;
- `staleAfterTicks > 0`;
- `maxTenantTagValues > 0`, with `TenantMetricTagPolicy` clamping effective
  output to `MAX_LOCAL_TENANT_TAG_VALUES`;
- `eventHistoryLimit` in `1..MAX_EVENT_HISTORY_LIMIT`.

Use named wrappers for same-typed tick values. `TenantLogicalTick` represents a
single logical tick, and `TenantLeaseWindow(acquiredAt, renewedAt, expiresAt)`
groups lease timestamps so public APIs do not expose positional `Long`/`Int`
triplets such as `acquiredTick`, `lastRenewedTick`, and `expiresAtTick`.

`TenantScheduleTick` is the deterministic scenario input. It includes the logical
`tick`, ordered candidate `TenantNodeId` values, selected due tenants when a test
needs to narrow the tick, tenant action failures, and optional initial/stale
lease setup. Tick and scenario input validation must make ordering deterministic
and reject ambiguous duplicate node/tenant entries after canonicalization,
including duplicate due tenants, candidate nodes, initial lease entries, and
failure entries.

Tests use `bluetape4k-assertions`, including
`io.bluetape4k.assertions.assertFailsWith`, and must not use JUnit
`assertThrows`, AssertJ, Kluent, or `kotlin.test` assertions.

`TenantLockNamePlanner` uses `TenantLockNamespace(tenant.value).lockName(jobName)`
to derive backend lock names such as `tenant:tenant-a:invoice-sync`. The planner
also exposes metric tag rows so learners can see the difference between the
backend lock identity and bounded metric dimensions.

`TenantSchedulerLab` does not implement a distributed lock. It models the
observable scheduling contract:

1. Each tenant owns an independent logical lease state.
2. Each tick evaluates candidate nodes per tenant.
3. At most one node runs the tenant-local job for a tenant.
4. A failure in one tenant records a failed outcome but does not stop other
   tenants in the same tick.
5. A stale tenant lease can hand off to another node without changing other
   tenant leases.
6. With the default capacity, all due tenants are evaluated in the same tick;
   there is no hidden one-global-lock bottleneck.
7. When a test intentionally sets capacity below the number of due tenants, the
   lab chooses tenants by `lastSelectedTick` ascending, then tenant id, so starvation
   behavior is deterministic and testable.
8. Within each tenant, candidate nodes are evaluated in deterministic order and
   stale/failing ownership affects only that tenant's lease state.

Reports should stay bounded and readable. The scenario report keeps the event
rows needed to explain the README scenario, plus summarized counters per tenant
and outcome. Stress-style tests should assert summary counters and a bounded
history limit instead of storing every possible tenant/node/tick detail forever.
Every report row, README example, sequence label, and diagram caption uses the
same non-sensitive tenant alias policy as metrics.

Report history is bounded by explicit constants:

- `DEFAULT_EVENT_HISTORY_LIMIT = 64`;
- `MAX_EVENT_HISTORY_LIMIT = 512`;
- when more events are produced than the configured limit, the report keeps the
  first explanatory rows, sets `truncated=true`, and increments
  `droppedEventRows`.

### Tick Reducer And Lease Transitions

The default lab is a finite pure logical-tick reducer:

- no wall clock, background scheduler, owned executor, `GlobalScope`,
  `Thread.sleep`, coroutine `delay`, Awaitility, or polling loop in default
  tests;
- retries and deadlines, when demonstrated, are tick-counted input fields and
  report outcomes, not real timers;
- `TenantSchedulerLab.run(policy, ticks)` returns a new immutable
  `TenantSchedulerReport` and does not retain static mutable state between
  runs.

Each logical tick is reduced in this order:

1. Canonicalize and validate policy/tick input.
2. Select due tenants. Default capacity selects every due tenant. Bounded
   capacity sorts by `lastSelectedTick` ascending, then tenant alias, and selects at
   most `maxTenantsPerTick`.
3. Evaluate selected tenants in that deterministic order. A failed tenant action
   records a failed outcome but never aborts later tenant evaluations in the
   same tick.
4. Evaluate candidate nodes for each tenant in input order, applying only that
   tenant's lease state.
5. Append bounded event rows in reducer order and update summary counters.

`TenantLeaseState` contains tenant alias, lock name, owner node alias,
`TenantLeaseWindow`, and the last outcome. A lease is active while
`currentTick < expiresAtTick`; handoff is allowed when
`currentTick >= expiresAtTick`.

Lease transitions are ordered and testable:

| Prior state | Candidate condition | Outcome | State update |
|-------------|---------------------|---------|--------------|
| no lease | first candidate succeeds | `executed` | acquire owner, set acquired/renewed to current tick, set expiry to current tick + stale threshold |
| no lease | first candidate fails | `failed` | acquire owner for the failed action, set acquired/renewed to current tick, set expiry to current tick + stale threshold |
| active lease | active owner is present and succeeds | `executed` | keep acquired tick, set renewed to current tick, extend expiry to current tick + stale threshold |
| active lease | active owner is present and fails | `failed` | keep acquired/renewed/expiry unchanged; do not renew |
| active lease | active owner is absent | `skipped` | no handoff and no state change before expiry |
| active lease | non-owner candidate is evaluated before expiry | `skipped` | no state change |
| expired lease | first candidate succeeds | `stale-handoff` | replace owner, set acquired/renewed to current tick, set expiry to current tick + stale threshold |
| expired lease | first candidate fails | `failed` | replace owner for the failed action, set acquired/renewed to current tick, set expiry to current tick + stale threshold |

Failed action behavior is explicit: an already-active failed tenant records
`failed`, keeps its prior lease until `expiresAtTick`, and becomes eligible for
handoff only at the same stale boundary. Other tenant leases are not modified.

Fairness state is explicit. A never-selected tenant sorts first with a sentinel
`lastSelectedTick = TenantLogicalTick.MIN`. Every selected tenant updates
`lastSelectedTick` after evaluation, regardless of `executed`, `failed`,
`skipped`, or `stale-handoff` outcome. Purely unselected tenants keep their prior
sentinel or tick. Bounded-capacity tests must prove rotation across repeated
logical ticks, not only a single-tick ordering.

Outcome dimensions are bounded and named: `executed`, `skipped`, `failed`,
`stale-handoff`, and `deadline-or-retry-exhausted`.

### Build Dependency Contract

Production dependencies should stay small and versionless through the root BOM:

- allowed: `bluetape4k-core`, `bluetape4k-leader-core`,
  `bluetape4k-logging`, Spring Boot autoconfigure/actuator if the module keeps
  the same runnable workshop shape as nearby leader modules;
- allowed tests: `shared`, `bluetape4k-junit5`,
  `bluetape4k-assertions`, Spring Boot starter test, and MockK only if a test
  truly needs mocks;
- forbidden in this module's default path: Redis, ZooKeeper, Kubernetes,
  PostgreSQL clients, Testcontainers, Awaitility, individual bluetape4k module
  BOMs, and explicit bluetape4k versions.

Public workshop-facing classes and functions need English KDoc with a summary,
behavior contract, and a realistic snippet. Implementation-only helpers should
be `internal`.

### Metrics Guidance

`TenantMetricTagPolicy` should keep the workshop honest about per-tenant tags.
The local lab may show tenant tags because the tenant set is fixed and small,
but the README must warn that production systems should bound tenant tag values,
aggregate them, or use exemplar/log correlation when tenant cardinality is
large.

The policy contract is explicit:

- `DEFAULT_MAX_TENANT_TAG_VALUES = 16`;
- `MAX_LOCAL_TENANT_TAG_VALUES = 100`;
- if `tenantCount <= maxTenantTagValues` and `maxTenantTagValues` is within the
  hard local cap, metric rows may include `tenant=<tenantAlias>`;
- if `tenantCount > maxTenantTagValues` or the requested limit exceeds the hard
  local cap, per-tenant metric rows are suppressed and the safe tag becomes
  `tenant=bounded`;
- the report records a `cardinalityLimited=true` warning with the configured
  limit and actual tenant count;
- tests must prove that unsafe input does not emit more than the configured
  tenant-tag bound or hard local cap;
- metric tag keys are allow-listed. Do not emit backend lock names, raw job
  names, node ids, emails, account-id-shaped aliases, or arbitrary tenant input
  as metric dimensions.

### README Content Contract

Both `README.md` and `README.ko.md` must be source-equivalent and include:

- language switch directly below the title;
- a tested Kotlin snippet that constructs `TenantSchedulePolicy`, runs
  `TenantSchedulerLab` with logical ticks, inspects lock names/outcomes/metric
  tag decisions, and shows the expected report shape;
- the same architecture and sequence diagrams with meaningful alt text, PNG
  embeds, SVG source colocated in `docs/images/readme-diagrams/`, and a short
  learner takeaway beside each diagram;
- a safe/unsafe metric-tag table covering bounded tenant sets,
  high-cardinality tenant identifiers, `tenant=bounded`,
  aggregation/exemplar alternatives, and the lab's configured limit;
- an explicit unsupported/production-boundary section: no distributed
  coordination, no real `LeaderElector` execution, no persistence, no wall-clock
  scheduling, no retry/backoff engine, and no real metrics exporter;
- a runbook-style section that explains how to reset/rerun the finite lab safely,
  how to interpret `cardinalityLimited=true`, and what a learner should do after
  a failed or stale-lease scenario before mapping the idea to a real backend;
- migration pointers to `TenantScopedLeaderElectors.forTenant(...)`,
  `TenantLockNamespace`, `spring-boot/multi-tenant-data-isolation`,
  `leader/backend-comparison-lab`, `bluetape4k-leader/examples/tenant-aggregator`,
  and the real Redis/ZooKeeper/Kubernetes leader backend modules.
- snippets copied from `TenantSchedulerReadmeSnippetTest` or an equivalent test
  fixture so README code is compile-verified.
- unsafe identifiers shown only as redacted tokens such as
  `<email-redacted>` or `acct-<redacted>`.

### Diagrams

Create two README diagrams under `docs/images/readme-diagrams/`:

1. `leader-tenant-scheduler-readme-architecture-01.svg/png`
   - Static ownership view: learner input, tenant lock-name planner, scheduler
     lab, independent tenant lease states, metric tag policy, and real leader
     backend practice boundary.
   - Include visible boundary text such as "Lab model: no distributed lock" and
     "Production backends: Redis/ZooKeeper/Kubernetes/tenant-aggregator".
   - Use layers or lanes only when they simplify the ownership story.
   - If connector styles differ, include a visible legend or adjacent README
     explanation.
2. `leader-tenant-scheduler-readme-sequence-01.svg/png`
   - Established bluetape4k best-practices sequence style.
   - Show a scheduled tick, tenant A leadership, tenant B failure isolation,
     stale lease handoff, and report/metric emission.
   - Use numbered call labels, transparent `alt`/`else` region bodies, muted
     sequence palette, enough row height, and line-colored arrowheads.

Diagram work must pass the current `$bluetape4k-diagram` checklist, repo-local
diagram QA wrapper, SVG XML validation, CairoSVG PNG rendering, full-size PNG
visual inspection, marker/color audits, sequence style audit, and connector
geometry audits where applicable.

## Non-Goals

- Do not start Redis, ZooKeeper, Kubernetes, PostgreSQL, LocalStack, or any
  Testcontainers service in default tests.
- Do not create a production scheduler framework.
- Do not duplicate `bluetape4k-leader/examples/tenant-aggregator` R2DBC runtime
  behavior.
- Do not add a separate shard abstraction or shard-specific tests in this issue.
- Do not add an individual `bluetape4k-leader` BOM or explicit bluetape4k module
  versions.
- Do not add backend leader implementation clients, Redis/ZooKeeper/Kubernetes
  clients, PostgreSQL clients, Testcontainers, Awaitility, or module-local BOMs
  to the default module.
- Do not use `awaitility`; deterministic scenarios should use direct state
  assertions.
- Do not make unbounded per-tenant metric tags look production-safe.
- Do not use PII, customer names, emails, or raw account ids as tenant ids in
  examples, metric tags, report rows, logs, or diagrams.
- Do not hide background mutable state or retain lease/report data between lab
  runs.

## Risks And Mitigations

| Risk | Mitigation |
|------|------------|
| The lab diverges from real tenant leader APIs. | Use `TenantLockNamespace` directly for lock-name derivation and link to `forTenant(...)` wrappers in README. |
| Learners mistake the simulator for a production scheduler. | Name classes and docs as a lab; state that real backends belong in `bluetape4k-leader` practice modules. |
| Tenant identifiers leak sensitive data through lock names, logs, metrics, or diagrams. | Accept only synthetic metric/log-safe aliases and document the production aliasing requirement. |
| Metric cardinality guidance is too casual. | Add explicit safe/unsafe tag policy tests and README operational limits. |
| Fairness/stale-lock behavior becomes nondeterministic. | Use logical ticks and immutable input sequences; avoid sleeps and wall-clock timing. |
| One tenant failure short-circuits unrelated tenants. | Define reducer ordering and test that later tenants still run after an earlier failed tenant. |
| Report history grows without bound in stress scenarios. | Enforce `eventHistoryLimit`, hard cap `MAX_EVENT_HISTORY_LIMIT`, truncation fields, and a deterministic stress test over many tenants/nodes/ticks. |
| New module is omitted from CI/smoke validation. | Update root README locale rows, `scripts/smoke-validate.sh all-smoke`, stale-check expected count, `.github/workflows/Examples.yml` paths/jobs/artifacts, and verify `./gradlew projects`. |
| Diagrams drift from best-practices style. | Open best-practices references first, render SVG to PNG, inspect every touched PNG full-size, and record concrete audit evidence. |

## Acceptance Criteria

- `leader/tenant-scheduler` exists and is listed as `:leader-tenant-scheduler`.
- The build uses the root `bluetape4k-dependencies` BOM only and versionless
  catalog aliases.
- Default tests are deterministic and start no infrastructure.
- Tests verify two tenants coordinate independently.
- Tests verify one tenant failure does not block another tenant.
- Tests verify stale lease handoff for one tenant without disturbing another
  tenant.
- Tests verify default capacity lets all due tenants progress in one tick and
  bounded capacity uses deterministic least-recently-run tenant ordering.
- Tests verify bounded capacity rotates fairly across repeated logical ticks
  using explicit `lastSelectedTick` sentinel/update semantics.
- Tests verify same input produces the same report ordering every run.
- Tests verify failure in one tenant does not short-circuit later tenant
  evaluations in the same tick.
- Tests verify stale leases do not hand off before `expiresAtTick`, do hand off
  when `currentTick >= expiresAtTick`, and preserve unrelated tenant leases.
- Tests verify failed action retains the failed tenant lease until the defined
  stale boundary, then allows handoff by the same rules.
- Tests verify two lab instances and two consecutive runs with the same input do
  not share lease/report state.
- Tests verify lock names are derived through `TenantLockNamespace` and invalid
  tenant/job values fail fast.
- Tests verify tenant aliases and job names reject blanks, colons, uppercase
  drift after canonicalization, control characters, whitespace, metric/log
  delimiters, email-like values, account-id-shaped values, and over-length
  values.
- Tests verify validation exception messages do not include raw rejected emails,
  account-id-shaped values, or control-character input.
- Tests verify duplicate tenants, due tenants, candidate nodes, initial leases,
  and failure entries are rejected after canonicalization.
- Tests verify `TenantNodeId`, `TenantSchedulePolicy`, `TenantScheduleTick`, and
  bounded report settings reject invalid or ambiguous values with
  `IllegalArgumentException` and bluetape4k assertion helpers.
- Tests verify metric tag policy emits per-tenant tags only within
  `maxTenantTagValues` and `MAX_LOCAL_TENANT_TAG_VALUES`, otherwise emits
  `tenant=bounded` plus a cardinality warning.
- Tests verify metric tags use only allow-listed keys and do not include lock
  names, job names, node ids, emails, account-id-shaped aliases, or arbitrary raw
  tenant input.
- Tests and README examples use only synthetic non-sensitive aliases, and docs
  explicitly warn not to put PII/customer names/emails/account ids in lock names,
  metric tags, logs, reports, or diagrams.
- Tests verify invalid `TenantSchedulePolicy` values fail fast, while excessive
  tenant cardinality degrades safely through `tenant=bounded` and a report
  warning.
- Tests include a fixed-input logical-tick stress scenario over many tenants,
  nodes, and ticks that asserts no starvation, bounded report rows, bounded
  metric cardinality, and no infrastructure or sleeps.
- Tests verify report truncation sets `truncated=true`, increments
  `droppedEventRows`, and keeps `eventRows.size <= eventHistoryLimit`.
- Implementation and default tests use logical ticks and fixed inputs only; no
  `Thread.sleep`, wall-clock polling, timers, scheduler delays, or Awaitility.
- `README.md` and `README.ko.md` explain lock naming, metric cardinality risk,
  operational limits, and relation to real leader backend modules.
- `README.md` and `README.ko.md` are source-equivalent: same examples, diagrams,
  operational warnings, unsupported guarantees, and migration pointers.
- README runbook guidance explains lab reset/rerun, cardinality warnings, and
  failed/stale-lease scenario interpretation.
- README snippets are copied from compile-verified test code.
- Public classes/functions have English KDoc with contract and realistic usage
  snippets; implementation-only helpers are `internal`.
- README diagrams exist as SVG+PNG and pass the diagram checklist plus visual
  inspection.
- Root `README.md` and `README.ko.md` list the module.
- CI/example validation includes the new deterministic module in smoke scope and
  path filters.

## Validation

- `./gradlew :leader-tenant-scheduler:test --no-build-cache --rerun-tasks`
- `./gradlew :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --warning-mode all`
- `./gradlew projects --console=plain`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-readme-language.mjs`
- `./scripts/smoke-validate.sh stale-check`
- `./scripts/smoke-validate.sh all-smoke`
- `./scripts/smoke-validate.sh diagram-qa`
- `rg -n ":leader-tenant-scheduler:test" scripts/smoke-validate.sh .github/workflows/Examples.yml`
- `if rg -n "testcontainers|awaitility|redis|zookeeper|kubernetes|postgres|bom\\(|version\\(" leader/tenant-scheduler/build.gradle.kts; then exit 1; fi`
- `if rg -n "Thread\\.sleep|delay\\(|GlobalScope|@Scheduled|ScheduledExecutorService|Executors\\.|scheduleAtFixedRate|Timer\\(|System\\.currentTimeMillis|Instant\\.now|Clock\\.system|CoroutineScope\\(|launch\\(|async\\(" leader/tenant-scheduler/src; then exit 1; fi`
- `if rg -n "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\b(?:acct|aws|customer)-[0-9]{6,}\\b" leader/tenant-scheduler/README.md leader/tenant-scheduler/README.ko.md leader/tenant-scheduler/src/main leader/tenant-scheduler/src/test/resources docs/images/readme-diagrams README.md README.ko.md; then exit 1; fi`
- `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml`
- `git diff --check`
