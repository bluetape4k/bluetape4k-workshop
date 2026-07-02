# Issue #329 Code Review

**Date**: 2026-07-02
**Scope**: `leader/tenant-scheduler` plus README, diagram, smoke, and Examples workflow registration.
**Review stance**: 7-tier review after implementation, diagram repair, and validation.

## Findings

P0 findings: 0
P1 findings: 0
P2 findings: 0
P3 findings: 0

Resolved review findings:

- P1 untracked implementation/assets: addressed by staging and committing the
  new module, diagrams, docs, workflow, and smoke changes before PR creation.
- P2 privacy validation command scanned required negative test fixtures: fixed
  the spec command so it scans README/main/resources/runtime artifacts while
  excluding `src/test/kotlin` negative fixtures.
- P2 stale handoff isolation lacked a combined scenario: added
  `stale handoff does not disturb unrelated tenant lease`.

## 7-Tier Review

| Tier | Result | Evidence |
|------|--------|----------|
| Performance | PASS | The lab is a pure logical-tick reducer and does not start Redis, ZooKeeper, Kubernetes, Docker, network clients, or background schedulers. The stress test keeps reports bounded with `eventHistoryLimit`. |
| Stability | PASS | `TenantSchedulePolicy` rejects empty/duplicate tenants and invalid numeric bounds. `TenantScheduleTick` rejects duplicate candidates, due tenants, action failures, and seed leases. `TenantSchedulerLab` also rejects seed leases whose lock names do not match the policy job. |
| Security/privacy | PASS | Tenant, job, and node aliases are canonicalized and reject whitespace, control characters, email-like values, and account-id-shaped identifiers without echoing raw unsafe input. Metric tags degrade to `tenant=bounded` when cardinality is unsafe. |
| Operator | PASS | README states that the module is infrastructure-free and production systems must replace the reducer with `TenantScopedLeaderElectors` and a selected backend. Smoke validation and Examples workflow include the module. |
| Developer/API | PASS | Public value types use named wrappers and `Serializable`, avoid same-type raw parameter APIs in the scheduler contract, and use bluetape4k validation helpers for common numeric/string validation. Tests use JUnit 5 plus bluetape4k assertions. |
| User/learner | PASS | README and README.ko include language switches, architecture and sequence diagrams, an executable snippet, scenario table, test map, run commands, and production boundaries. |
| Current-session integration | PASS | Spec, plan, module registration, root README rows, smoke script, Examples workflow, diagrams, and review evidence are consistent with issue #329 and milestone 1.3.1 scope. |

## Verification Evidence

- RED identifier/planner/scheduler checks failed before production classes were added.
- GREEN unit slices passed for identifier validation, lock-name planning, metric-tag policy, scheduler scenarios, and README snippet execution.
- Module test: `./gradlew --no-daemon :leader-tenant-scheduler:test --no-build-cache --rerun-tasks --console=plain` passed with 19 tests.
- Compile: `./gradlew --no-daemon :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --warning-mode all --console=plain` passed. The visible warnings are existing root Gradle Kotlin DSL deprecations, not touched module source warnings.
- Projects: `./gradlew --no-daemon projects --console=plain` passed and listed `:leader-tenant-scheduler`.
- Smoke: `./scripts/smoke-validate.sh all-smoke` passed with `BUILD SUCCESSFUL` and 288 actionable tasks.
- Stale check: `./scripts/smoke-validate.sh stale-check` reported 99/99 modules, no stale refs, and no broken README image links.
- Diagram QA: `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/leader-tenant-scheduler-readme-architecture-01.svg docs/images/readme-diagrams/leader-tenant-scheduler-readme-sequence-01.svg` passed with `targets=2`, `weak_reference_rows=0`, architecture `q_bends=12`, sequence `markers_checked=8`, `labels=8`, `numbers=8`, `alt_fill_failures=0`, and `sequence style reference audit: PASS`.
- Sequence label repair: a rendered-PNG eye review initially failed label/line spacing. The SVG now has 32px gap from every label pill bottom to its own call line for labels 1-8, and a follow-up independent vision review returned PASS.
- Eye inspection: both rendered PNGs were opened full-size after the final coordinate change. Architecture has no broken icons, text overlap, connector intrusion, or missing legend. Sequence has separated labels, matching arrowhead colors, transparent alt body, and readable activation/lifeline structure.
- Workflow: `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml` passed.
- Whitespace: `git diff --check` passed.
- Code-pattern scan: no production `runBlocking`, `runCatching`, `GlobalScope`, broad coroutine cancellation traps, `synchronized`, or direct Testcontainers usage exists in the new module.

## Residual Risk

The module models tenant-scoped scheduling semantics but does not prove a real
distributed lock backend under contention. That is intentional for a default
smoke-safe workshop lab; backend-heavy practice remains in Redis, ZooKeeper, and
Kubernetes Lease modules.
