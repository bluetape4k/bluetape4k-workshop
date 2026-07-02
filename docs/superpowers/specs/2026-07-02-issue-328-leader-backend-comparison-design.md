# Issue #328 - Leader Backend Comparison Lab Design

**Date**: 2026-07-02
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/328
**Milestone**: 1.3.1
**Status**: Ready for implementation planning

## Goal

Add a `leader/backend-comparison-lab` workshop module that helps learners choose
between Redis, ZooKeeper, and Kubernetes Lease leader-election backends.

The module must not replace the existing backend-specific examples:

- `leader/leader-election` remains the Redis runnable integration example.
- `leader/leader-zookeeper` remains the ZooKeeper runnable integration example.
- `leader/k8s-lease-micrometer` remains the Kubernetes Lease + Micrometer
  runnable example.

Instead, this lab provides a source-backed comparison layer: one common
leader-guarded scheduled-job model, deterministic failover/handoff scenarios,
backend capability tables, and README diagrams that connect the three existing
examples.

## Source Evidence

| Source | Evidence |
|--------|----------|
| GitHub issue #328 | Requires a side-by-side backend comparison, common guarded job abstraction, failover/handoff scenarios, metrics/event comparison, stable-vs-preview guidance, README locale parity, and deterministic default tests. |
| `leader/leader-election/README.md` | Redis uses `LettuceLeaderElector`, `ListeningLeaderElector`, `LettuceSuspendLeaderElector`, `runIfLeader(lockName)`, TTL-shaped `waitTime`/`leaseTime`, and event listener/Flow observation. |
| `leader/leader-zookeeper/README.md` | ZooKeeper uses Curator, session-bound ephemeral znodes, single-leader and group-leader variants, and has no Redis-style TTL. Failover follows `sessionTimeoutMs`. |
| `leader/k8s-lease-micrometer/README.md` | Kubernetes Lease path is opt-in; default tests use `DisabledLeaderCoordinator`. The module records application-level meters and names upstream `leader-micrometer` meters. |
| `docs/lessons/2026-05-23-issue-106-leader-election.md` | Correct Redis package path is `io.bluetape4k.leader.lettuce.LettuceLeaderElector`; `runIfLeader` returns `null` on skip; Redis tests should use `RedisServer.Launcher.redis` when backend integration is needed. |
| `docs/lessons/2026-05-25-leader-zookeeper.md` | ZooKeeper has no TTL; avoid copying Redis `leaseTime` assumptions; use session-loss semantics for failover explanation. |
| `docs/lessons/2026-06-29-issue-289-k8s-lease-micrometer.md` | Keep real Kubernetes access opt-in; default smoke path must remain deterministic. |
| `bluetape4k-leader` source | `LeaderElector.runIfLeader` executes action only when elected and returns `null` when not acquired; `LeaderElectionOptions` validates with bluetape4k helpers; Kubernetes options validate namespace and retry delay. |
| `bluetape4k-leader` benchmark docs | Cross-backend comparisons are local evidence, not production rankings; README recommendations must keep caveats near comparison tables. |

## Brainstorming Summary

### Approach A - Documentation-only comparison

Create only README pages and diagrams that summarize existing modules.

**Rejected**: It would satisfy the comparison matrix but would not provide
deterministic failover/handoff scenario tests, so learners could not run the
lab locally.

### Approach B - Full runnable backend matrix

Start Redis, ZooKeeper, and Kubernetes Lease backends in one module and run the
same job through every real elector.

**Rejected**: It would duplicate the three existing modules, make default CI
container-heavy, and make Kubernetes credentials a default-path risk. This also
violates the issue requirement that backend-heavy checks be tagged or scoped
outside default tests.

### Approach C - Deterministic comparison lab with opt-in backend links

Create a small Spring Boot workshop module with:

- a common `LeaderGuardedSchedulerLab` model;
- backend profiles for Redis, ZooKeeper, and Kubernetes Lease;
- deterministic scenario simulation for elected, skipped, failed, expired, and
  handed-off attempts;
- metrics/event catalog rows that match the real examples;
- README/README.ko and generated diagrams pointing learners to the real
  backend modules for hands-on integration.

**Selected**: This satisfies #328 without replacing the existing examples. It
keeps default tests local and deterministic while still teaching real
backend-selection trade-offs.

## Design

### Module

```text
leader/backend-comparison-lab/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/
    BackendComparisonLabApp.kt
    domain/BackendProfile.kt
    domain/BackendCapability.kt
    domain/LeaderScenario.kt
    domain/LeaderScenarioReport.kt
    service/LeaderBackendCatalog.kt
    service/LeaderFailoverLab.kt
  src/main/resources/application.yml
  src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/
    service/LeaderBackendCatalogTest.kt
    service/LeaderFailoverLabTest.kt
  src/test/resources/junit-platform.properties
  src/test/resources/logback-test.xml
```

The Gradle project is auto-registered by `includeModules("leader", false, true)`
as `:leader-backend-comparison-lab`.

### Runtime Model

`LeaderBackendCatalog` provides immutable, source-backed backend profiles:

- Redis Lettuce: stable backend; TTL/lease-based recovery; event listener and
  Flow observation available through `ListeningLeaderElector`.
- ZooKeeper Curator: stable backend; session-bound recovery; single-leader and
  group-leader paths; no Redis-style TTL.
- Kubernetes Lease: preview/opt-in workshop backend; Kubernetes API object
  ownership; Micrometer decorator and application-level meters.

`LeaderFailoverLab` runs deterministic scenarios against these profiles. It
does not implement a distributed lock. It models the learner-visible contract:
which node runs, which nodes skip, which state change causes handoff, and which
metric/event row should be inspected.

### Scenarios

| Scenario | Purpose | Expected local behavior |
|----------|---------|-------------------------|
| `steady-leader` | One instance wins and executes the guarded job. | Report has one `executed=true` event and follower skips. |
| `contention-skip` | Followers do not run the same scheduled job. | Report records skipped followers with backend-specific reason text. |
| `action-failure-release` | A failing guarded action should not hide the next eligible run. | Report records failure then recovery/handoff to next candidate. |
| `backend-loss-handoff` | Backend-specific failover trigger differs by backend. | Redis uses lease expiry, ZooKeeper uses session loss, Kubernetes uses Lease expiry/resource update. |

### Backend Matrix

README tables must distinguish:

- backend primitive;
- failover trigger;
- expected failover tuning knob;
- metrics/events available in workshop modules;
- default test scope;
- when to choose;
- when not to choose;
- target module for real backend practice.

The module should describe local comparison evidence as learning guidance, not
as production performance ranking.

### Diagrams

Create two README diagrams under `docs/images/readme-diagrams/`:

1. `leader-backend-comparison-lab-readme-architecture-01.svg/png`
   - Static ownership view: learner, comparison lab, backend profiles, existing
     Redis/ZooKeeper/Kubernetes modules, metric/event notes.
   - Must include a legend if connector styles differ.
2. `leader-backend-comparison-lab-readme-sequence-01.svg/png`
   - Established best-practices sequence style.
   - Shows one scheduled tick, leader execution, follower skip, backend-loss
     handoff, and metric/event capture.
   - Must use transparent `alt`/`else` bodies, numbered call labels, muted
     palette, and arrowheads matching their line colors.

Diagram work must pass the current `$bluetape4k-diagram` checklist, repo-local
diagram QA wrapper, SVG XML validation, CairoSVG PNG rendering, full-size PNG
visual inspection, marker/color audits, sequence style audit, and connector
geometry audits where applicable.

## Non-Goals

- Do not implement a new real leader-election backend.
- Do not start Redis, ZooKeeper, Kubernetes, or LocalStack in default tests.
- Do not replace or rename existing leader modules.
- Do not add an individual `bluetape4k-leader` BOM or explicit bluetape4k
  module versions.
- Do not publish production benchmark rankings.
- Do not add `awaitility`; deterministic scenarios should use direct state
  assertions.

## Risks And Mitigations

| Risk | Mitigation |
|------|------------|
| The lab looks like a fake replacement for real backend modules. | README and class names must call it a comparison lab and link to real backend practice modules. |
| Scenario simulation drifts from real backend semantics. | Keep profiles small and source-backed; cite Redis TTL, ZooKeeper session, and Kubernetes Lease differences directly in README and tests. |
| Diagrams fail established visual style. | Start from current best-practices references, render SVG to PNG, visually inspect each touched PNG, and record checklist evidence. |
| New module is omitted from CI/smoke validation. | Update root README locale rows, `scripts/smoke-validate.sh all-smoke`, `.github/workflows/Examples.yml` paths/jobs/artifacts, and verify `./gradlew projects`. |
| TDD skipped because logic appears simple. | Add failing tests for catalog and failover reports before production code; record red/green evidence. |

## Acceptance Criteria

- `leader/backend-comparison-lab` exists and is listed as
  `:leader-backend-comparison-lab`.
- The build uses the root `bluetape4k-dependencies` BOM only and versionless
  catalog aliases.
- Default tests are deterministic and do not start real backend containers.
- Tests cover backend profile matrix, scenario report ordering, skip reasons,
  action failure recovery, and backend-loss handoff explanation.
- `README.md` and `README.ko.md` include language switches and source-equivalent
  backend selection matrices.
- README diagrams exist as SVG+PNG and pass the diagram checklist plus visual
  inspection.
- Root `README.md` and `README.ko.md` list the module.
- CI/example validation includes the new deterministic module in smoke scope and
  path filters.
- The module explicitly links learners to `leader-election`,
  `leader-zookeeper`, and `k8s-lease-micrometer` for real backend practice.

## Validation

- `./gradlew :leader-backend-comparison-lab:test --no-build-cache --rerun-tasks`
- `./gradlew :leader-backend-comparison-lab:compileKotlin :leader-backend-comparison-lab:compileTestKotlin --warning-mode all`
- `./gradlew projects --console=plain`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-readme-language.mjs`
- `./scripts/smoke-validate.sh stale-check`
- `./scripts/smoke-validate.sh diagram-qa`
- `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml` when workflow files change
- `git diff --check`
