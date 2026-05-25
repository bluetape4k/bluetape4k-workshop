# Implementation Plan — Issue #85 Observability/Performance Advanced Completion

**Date**: 2026-05-26
**Branch**: `feat/issue-85-observability-performance`
**Spec**: `docs/superpowers/specs/2026-05-26-issue-85-observability-performance-design.md`

## Task List

| ID | Task | Files | Verification |
|---|---|---|---|
| T1 | Replace `observed()` with coroutine-aware scoped observation wrapper | `observability/observability-advanced/src/main/kotlin/.../ObservationSupport.kt` | compile + parent-child test |
| T2 | Add cache-miss parent-child span regression test | `observability/observability-advanced/src/test/kotlin/.../UserServiceTest.kt` | `:observability-advanced:test` |
| T3 | Update English README for issue #85 Bluetape4k-first AC | `observability/observability-advanced/README.md` | source-name grep + markdown review |
| T4 | Update Korean README to match English README user-facing changes | `observability/observability-advanced/README.ko.md` | source-name grep + markdown review |
| T5 | Run targeted verification | no source edits | `./gradlew :observability-advanced:test` |
| T6 | Run Step 6-R dual code review | `.omx/artifacts/*` | Codex review + Claude Code CLI artifact P0/P1 = 0 |
| T7 | Capture lesson, commit, push, create PR | `docs/lessons/2026-05-26-issue-85-observability-performance.md` | `git status`, PR URL |

## Implementation Constraints

- Do not change dependency versions or add new dependencies.
- Do not run Gatling load tests in CI-like verification; document smoke/load
  commands and stop conditions instead.
- Do not touch generated README diagram assets in this pass.
- Keep README public API/code references aligned with actual class/function names.
- Keep Kotlin KDoc in English and conversation/internal plan text Korean-friendly.

## Validation Commands

```bash
./gradlew :observability-advanced:test
rg -n "observed\\(|Used Bluetape4k|structuredTaskScopeAll|Dispatchers.VT|gatlingRun" \
  observability/observability-advanced/README.md \
  observability/observability-advanced/README.ko.md \
  observability/observability-advanced/src/main/kotlin \
  observability/observability-advanced/src/test/kotlin
git diff --check
```

## Review Gates

1. Step 2-R / 3-R: local spec/plan review plus Claude Code CLI advisor.
2. Step 6-R: current Codex diff review plus Claude Code CLI code review.
3. P0/P1 findings block progress until fixed and rechecked.

## Stop Condition

Stop after PR creation and CI status report. Do not merge automatically.

## Step 3-R Review Notes

Claude Code CLI review-style prompts repeatedly returned empty artifacts in this
Codex App session. The usable focused advisor artifact is:

- `.omx/artifacts/claude-issue-85-spec-plan-blockers-20260526055825.md`

Plan integration:

| Priority | Finding | Plan response |
|---|---|---|
| P1 | Coroutine scope propagation can leak or lose current observation. | T1 implements `ThreadContextElement`; T2 proves parent-child spans. |
| P1 | Cancellation must remain structured. | T1 keeps explicit `CancellationException` rethrow. |
| P2 | Runtime proof must be concrete. | T5 runs `:observability-advanced:test`; T6 reviews the diff. |

Latest Step 3-R status: P0 = 0, P1 = 0 for the executable plan.
