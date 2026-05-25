# Issue #85 — Observability/Performance Advanced Completion Design

**Date**: 2026-05-26
**Branch**: `feat/issue-85-observability-performance`
**Repository**: `bluetape4k-workshop`
**Work type**: Type A — Full Design

## Context

Issue #85 asks the workshop to strengthen advanced observability/performance examples:

- demonstrate trace/span propagation across coroutine and reactive boundaries;
- decide and document retained Gatling / virtual-thread load examples;
- include sequence diagrams, metrics/traces/screenshots where useful;
- include load-test prerequisites and stop conditions;
- maximize supported `bluetape4k-*` APIs in the happy path;
- document a `Used Bluetape4k features` table plus before/after rationale.

PR #178 already updated several README files for `micrometer-observation`,
`micrometer-tracing-coroutines`, `gatling/virtualthread-simulation`,
`virtualthreads/spring-mvc-tomcat`, and `virtualthreads/spring-webflux`, but the
issue remains open. Current `origin/develop` still has a gap in
`observability/observability-advanced`: its README lacks the required
`Used Bluetape4k features` table and before/after explanation, and the local
`observed()` helper starts/stops observations without opening a Micrometer scope
across coroutine dispatcher boundaries. That means the documented span tree can
become independent observations rather than parent-child spans.

CodeGraph is not initialized for this repository, so structural impact review
uses current source inspection plus targeted Gradle verification.

## Scope

Primary implementation scope:

- `observability/observability-advanced/src/main/kotlin/.../observation/ObservationSupport.kt`
- `observability/observability-advanced/src/test/kotlin/.../service/UserServiceTest.kt`
- `observability/observability-advanced/README.md`
- `observability/observability-advanced/README.ko.md`
- `docs/lessons/2026-05-26-issue-85-observability-performance.md`

Review-only regression scope:

- `observability/micrometer-observation/README.md`
- `observability/micrometer-tracing-coroutines/README.md`
- `gatling/virtualthread-simulation/README.md`
- `virtualthreads/spring-mvc-tomcat/README.md`
- `virtualthreads/spring-webflux/README.md`

## Design

### D1. Coroutine-aware observation wrapper

Replace the current local `observed()` implementation with a helper that:

1. validates `name` with bluetape4k validation;
2. creates and starts a Micrometer observation;
3. opens the observation scope through a coroutine `ThreadContextElement`;
4. executes the suspend block inside `withContext(scopeElement)`;
5. rethrows `CancellationException` before recording an error;
6. records non-cancellation errors;
7. always stops the observation in `finally`.

This preserves the existing workaround for the upstream
`withObservationContextSuspending` happy-path stop issue while restoring
Micrometer current-observation propagation across `withContext(Dispatchers.IO)`.

### D2. Parent-child span proof

Add a regression test proving that `user.cache.get`, `user.db.find`, and
`user.cache.put` all have `user.service.get` as their parent observation context
on the cache-miss path. Keep existing tests for cache-hit DB skip, no leaked
current observation, and stop semantics.

### D3. README completion

Update English and Korean READMEs for `observability-advanced` with:

- `Used Bluetape4k features` table;
- raw framework approach vs bluetape4k-supported approach;
- concrete benefit explanation;
- coroutine/reactive boundary propagation notes;
- smoke and load commands, prerequisites, and stop conditions;
- clarification that Gatling/virtual-thread modules are retained because they
  teach supported `bluetape4k-virtualthread`, logging, and Testcontainers paths.

No new generated diagram asset is planned; existing PNG architecture asset is
retained and existing README diagrams are only referenced.

## Acceptance Criteria

| Criterion | Required evidence |
|---|---|
| `observed()` propagates current observation across coroutine dispatcher boundaries | New parent-child span test passes |
| Cancellation remains safe | Existing `CancellationException` behavior preserved in code path; test registry has no leaked current observation |
| `observability-advanced` satisfies issue #85 Bluetape4k-first README requirement | README/README.ko include feature table, before/after, benefits, smoke/load commands |
| Retained Gatling/virtual-thread modules are documented | README text names retained modules and stop-condition commands |
| No unrelated modules changed | `git diff --name-status` limited to scoped files |
| Targeted verification passes | `./gradlew :observability-advanced:test` |

## Risks

- `TestObservationRegistry` parent assertions depend on Micrometer test APIs.
  Mitigation: use `hasParentObservationContextSatisfying` from the local 1.16.5
  jar confirmed with `javap`.
- Redis-backed tests use Testcontainers and must run serially. Mitigation:
  one targeted Gradle invocation only.
- CodeGraph is unavailable. Mitigation: record the tooling gap and rely on
  targeted source inspection plus compile/test evidence.

## Step 2-R Review Notes

Claude Code CLI review-style prompts repeatedly returned empty artifacts in this
Codex App session, although `claude -p 'Return exactly OK'` succeeded. The usable
advisor artifact is:

- `.omx/artifacts/claude-issue-85-spec-plan-blockers-20260526055825.md`

Normalized findings:

| Priority | Finding | Decision |
|---|---|---|
| P1 | Scope can leak or fail to propagate if the coroutine context element is not restored correctly. | Accepted; implementation uses `ThreadContextElement` and closes the scope returned by `updateThreadContext`. |
| P1 | `CancellationException` must not be swallowed. | Accepted; implementation rethrows before `observation.error(e)`. |
| P2 | Child coroutines must inherit the context element. | Accepted; scope is installed with `withContext(...)`; parent-child behavior is verified by `TestObservationRegistry`. |

Latest Step 2-R status: P0 = 0, P1 = 0 after the planned implementation and test
proof.
