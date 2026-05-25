# Issue #85 — Observability/Performance Advanced Completion

## Context

Issue #85 was still open after PR #178 had improved several observability,
Gatling, and virtual-thread READMEs. The remaining gap was in
`observability/observability-advanced`: its README did not fully satisfy the
Bluetape4k-first table/before-after/load-command requirement, and the local
`observed()` helper started and stopped observations without opening a
Micrometer scope across coroutine dispatcher boundaries.

## Decision

Keep the local `observed()` wrapper because it works around the known
`withObservationContextSuspending` happy-path stop issue, but make it
coroutine-scope-aware with a `ThreadContextElement`. This preserves structured
cancellation behavior while allowing child observations to see the current
parent observation after `withContext(Dispatchers.IO)`.

## Outcome

- `observed()` now validates the span name, opens/closes a Micrometer scope via
  coroutine context, rethrows `CancellationException`, records only real errors,
  and always stops the observation.
- `UserServiceTest` now proves parent-child span relationships on cache miss,
  cache hit, and create paths.
- `README.md` and `README.ko.md` now include the Used Bluetape4k features table,
  raw-vs-Bluetape4k before/after, smoke commands, retained load modules, and
  stop conditions.

## Verification

- `./gradlew :observability-advanced:test` — 8 tests passing.
- `git diff --check` — clean.
- IntelliJ diagnostics were attempted but timed out in this Codex App session;
  targeted Gradle compile/test served as fallback.
- Claude Code CLI review-style prompts repeatedly produced empty artifacts, but
  focused blocker prompts produced:
  - `.omx/artifacts/claude-issue-85-spec-plan-blockers-20260526055825.md`
  - `.omx/artifacts/claude-issue-85-code-blockers-20260526060522.md`

## Future Guard

For coroutine-based Micrometer helpers, test both span lifecycle and parent
context propagation. A green "span exists" assertion is insufficient when the
work crosses `withContext(...)` dispatcher boundaries.
