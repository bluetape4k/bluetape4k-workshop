# Issue #328 Spec And Plan Review

**Date**: 2026-07-02
**Scope**: `leader/backend-comparison-lab` design and implementation plan
**Review type**: Local equivalent of Step 2-R and Step 3-R

Native child-agent spawning was not used because the current tool contract says
not to spawn subagents unless the user explicitly requests them. The same
required perspectives were reviewed in this session and recorded here as the
gate artifact.

## Step 2-R Spec Review

| Perspective | Result | Evidence |
|-------------|--------|----------|
| Completeness | PASS | The spec defines module purpose, non-goals, deterministic test boundary, README locale parity, diagrams, CI registration, and acceptance criteria. |
| Source grounding | PASS | The spec ties backend behavior to existing Redis, ZooKeeper, Kubernetes Lease modules, lessons, and `bluetape4k-leader` source semantics. |
| Boundary control | PASS | Default tests do not start Redis, ZooKeeper, Kubernetes, LocalStack, or any backend-heavy service. Existing runnable modules remain the real integration practice path. |
| Learner clarity | PASS | Backend matrix, scenario table, metrics/events table, and diagram requirements are explicit. |
| Ecosystem usage | PASS | The plan uses root BOM aliases, bluetape4k validation helpers, bluetape4k assertions, and existing leader module links. |
| Diagram readiness | PASS | Diagram requirements include the full bluetape4k diagram checklist, sequence best-practices, CairoSVG rendering, and full-size visual inspection. |

## Step 3-R Plan Review

| Perspective | Finding | Resolution |
|-------------|---------|------------|
| Performance | The first plan draft pulled Redis, ZooKeeper, Kubernetes, and Micrometer backend implementations into a deterministic comparison module. | Fixed. Production dependencies now stay limited to core/logging/Spring; backend modules are linked practice targets. |
| Stability | `LeaderBackendCatalog.findById` originally used `first`, which would leak `NoSuchElementException`. | Fixed. Plan now validates blank IDs and throws learner-friendly `IllegalArgumentException` for unknown backend IDs. |
| API design | `BackendCapability` was listed separately in the spec but embedded in the plan's `BackendProfile.kt` snippet. | Fixed. Plan now creates a dedicated `BackendCapability.kt`. |
| Code patterns | Empty-list validation used raw `require(...)`. | Fixed. Plan now uses `requireNotEmpty` from bluetape4k core. |
| Security/ops | Default runtime avoids credentials and networked backends. | PASS. Kubernetes remains opt-in in the existing practice module. |
| Documentation | README and diagram requirements are detailed enough to validate final learner assets. | PASS. |
| CI scope | Smoke and Examples workflow updates are included; nightly remains conditional only if scan proves it is needed. | PASS. |

## Gate Result

P0 findings: 0
P1 findings after fixes: 0
P2 findings: 0

The spec and plan are approved for TDD implementation.
