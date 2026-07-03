# Issue #383 Review: BackendB Programmatic Scheduler Lifecycle

## Scope

- Issue: #383 `Close BackendB programmatic scheduler lifecycle`
- Module: `spring-boot/resilience4j-coroutines`
- Changed build file:
  - `spring-boot/resilience4j-coroutines/build.gradle.kts`
- Changed production file:
  - `spring-boot/resilience4j-coroutines/src/main/kotlin/io/bluetape4k/workshop/resilience/controller/BackendBController.kt`
- Changed test file:
  - `spring-boot/resilience4j-coroutines/src/test/kotlin/io/bluetape4k/workshop/resilience/controller/BackendBControllerLifecycleTest.kt`

## Root Cause

`BackendBController` demonstrates programmatic Resilience4j `TimeLimiter` and `Retry` usage for `CompletableFuture` endpoints. The controller owned a dedicated `ScheduledExecutorService`, but the executor had no Spring shutdown hook. That left ownership ambiguous and could leak scheduler threads across application context shutdowns.

## Implementation Review

- The scheduler is now an explicit constructor dependency with a default owned scheduler for Spring runtime.
- The default scheduler uses `NamedThreadFactory("backend-b-programmatic-scheduler", isDaemon = true)` so thread purpose is visible and the workshop uses bluetape4k ecosystem infrastructure instead of an anonymous JDK pool.
- The module now declares `implementation(libs.bluetape4k.core)` directly because production code imports `io.bluetape4k.concurrent.NamedThreadFactory`.
- `@PreDestroy close()` shuts down the owned scheduler and isolates cleanup failure with `runCatching`, following bluetape4k lifecycle guidance.
- The future decorator path still uses the same scheduler for `withTimeLimiter(...)` and `withRetry(...)`; endpoint behavior is unchanged.
- The focused lifecycle test injects a test scheduler from `TestingExecutors`, verifies initial running state, calls `close()` twice, and asserts idempotent shutdown.

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| 1. Security | PASS | No new user input, secrets, auth, serialization, or external trust boundary. |
| 2. Architecture | PASS | Ownership is local to the controller and explicit; no new global bean or module boundary. |
| 3. Concurrency / Lifecycle | PASS | Scheduler ownership has `@PreDestroy`; cleanup is idempotent and tested. |
| 4. Code Quality | PASS | Small scoped diff, named constants, bluetape4k `NamedThreadFactory`, no ad hoc shared utility. |
| 5. Tests | PASS | `BackendBControllerLifecycleTest` covers shutdown; targeted TimeLimiter/Future tests verify existing path. |
| 6. Documentation / Learner Clarity | PASS | Code names the scheduler purpose; README behavior did not change, so README update is not required. |
| 7. Evidence Integrity | PASS | Baseline build, compile, targeted tests, and full build gates are recorded by log path below. |

## P0/P1 Gate

- P0: 0
- P1: 0
- P2/P3: none

## Validation Evidence

- Baseline before edits: `/tmp/issue383-baseline-build.log` — `BUILD SUCCESSFUL in 1m 17s`.
- Affected compile: `/tmp/issue383-affected-compile.log` — `BUILD SUCCESSFUL in 1s`.
- Targeted tests after lifecycle cleanup: `/tmp/issue383-targeted-tests-2.log` — `BUILD SUCCESSFUL in 24s`, `11 passing`.
- Targeted compile/tests after direct dependency declaration: `/tmp/issue383-targeted-tests-3.log` — `BUILD SUCCESSFUL in 4s`.
- Full build after work: `/tmp/issue383-full-build.log` — `BUILD SUCCESSFUL in 2m 59s`.
- `git diff --check` — PASS.
- CodeReviewGraph: repository registered but worktree graph was empty (`Files: 0`, `Last updated: never`), so review fell back to source diff, GNO context, compile, and tests.
