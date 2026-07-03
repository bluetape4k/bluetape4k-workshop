# Issue #381 BackendB TimeLimiter Review

Date: 2026-07-03
Scope: Issue #381, BackendB programmatic Reactor TimeLimiter coverage.

## Reviewed Diff

- `spring-boot/resilience4j-coroutines/src/main/kotlin/io/bluetape4k/workshop/resilience/controller/BackendBController.kt`
- `spring-boot/resilience4j-coroutines/src/test/kotlin/io/bluetape4k/workshop/resilience/timelimiter/TimeLimiterTest.kt`

## 7-Tier Findings

| Tier | Lens | Verdict | Evidence |
|---|---|---|---|
| 1 | Security | PASS | No authentication, authorization, user input parsing, serialization, or secret-handling behavior changed. |
| 2 | Architecture | PASS | The fix stays inside the existing BackendB programmatic Resilience4j controller and reuses the already configured `TimeLimiterRegistry` instance. No module boundary or dependency change. |
| 3 | Concurrency / Lifecycle | PASS | Reactor timeout uses Resilience4j `TimeLimiterOperator` and does not add threads or blocking work. Scheduler lifecycle cleanup is already present on the current base through issue #383; this diff does not rework it. |
| 4 | Code Quality / Correctness | PASS | `TimeLimiterOperator` wraps the retry/circuit-breaker/bulkhead pipeline for `Mono` and `Flux` fallback endpoints so a 3-second source delay is cut off by the configured 2-second limiter. |
| 5 | Tests | PASS | Added BackendB config and programmatic Mono/Flux timeout tests. New tests assert fallback body and elapsed time below the 3-second slow source delay. |
| 6 | Performance / Operations | PASS | BackendB slow Reactor timeout paths now return around 2 seconds instead of waiting for the full delayed publisher; no extra IO or external service requirement. |
| 7 | Documentation / Evidence | PASS | Public README/API docs did not change. Evidence is captured in this review artifact and targeted Gradle output. |

## P0/P1 Gate

- P0: 0
- P1: 0
- P2/P3: none.

## Validation Evidence

- `/tmp/issue381-baseline-build-retry.log`: repository baseline
  `./gradlew build`, `BUILD SUCCESSFUL in 1m 19s`.
- `/tmp/issue381-affected-compile.log`:
  `./gradlew :spring-boot-resilience4j-coroutines:compileKotlin :spring-boot-resilience4j-coroutines:compileTestKotlin --warning-mode all --max-workers=1 --console=plain`,
  `BUILD SUCCESSFUL in 2s`.
- `/tmp/issue381-timelimiter-test.log`:
  `./gradlew :spring-boot-resilience4j-coroutines:test --tests '*TimeLimiterTest*' --max-workers=1 --console=plain`,
  `BUILD SUCCESSFUL in 20s`, 8 tests; BackendB Mono/Flux programmatic timeout
  tests each passed in 2s.
- `/tmp/issue381-reactive-circuitbreaker-test.log`:
  `./gradlew :spring-boot-resilience4j-coroutines:test --tests '*ReactiveCircuitBreakerTest*' --max-workers=1 --console=plain`,
  PASS, 12 tests executed and 2 existing tests skipped; BackendB timeout repeat
  tests passed in 8s.
- `/tmp/issue381-full-build.log`: repository post-work `./gradlew build`,
  `BUILD SUCCESSFUL in 2m 56s`.
- `/tmp/issue381-diff-check.log`: `git diff --check`, PASS with no output.
