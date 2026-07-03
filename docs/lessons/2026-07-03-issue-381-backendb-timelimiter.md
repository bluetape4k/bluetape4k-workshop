# Issue 381 BackendB TimeLimiter Coverage

## Context

Issue #381 fixes a gap in the `spring-boot/resilience4j-coroutines` workshop example. BackendB wired a `TimeLimiterRegistry` but its programmatic Reactor `Mono` and `Flux` fallback chains did not apply the Reactor TimeLimiter operator. The existing circuit-breaker tests only asserted 2xx responses, so a slow publisher could complete without proving the configured 2-second timeout.

## Decision

- Keep the fix in `BackendBController` and reuse the existing `timeLimiter` from `TimeLimiterRegistry`.
- Apply `TimeLimiterOperator` outside the retry/circuit-breaker/bulkhead Reactor chain for the fallback timeout endpoints, so the whole slow reactive pipeline is bounded by the configured timeout.
- Add BackendB-specific tests in `TimeLimiterTest` because that test class owns timeout behavior better than circuit-breaker state tests.
- Assert both fallback response content and elapsed time below the 3-second source delay. Status-only checks are not sufficient for timeout examples.
- Keep this bug fix scoped to Reactor `TimeLimiterOperator` wiring. Scheduler
  lifecycle cleanup is already present on the current base through issue #383, so
  this change must not rework it.

## Outcome

BackendB programmatic `monoTimeout` and `fluxTimeout` now return fallback responses around the configured 2-second TimeLimiter window instead of waiting for the full delayed publisher.

## Verification

- `/tmp/issue381-baseline-build-retry.log`: repository baseline
  `./gradlew build`, `BUILD SUCCESSFUL in 1m 19s`.
- `/tmp/issue381-affected-compile.log`:
  `:spring-boot-resilience4j-coroutines:compileKotlin`
  `:spring-boot-resilience4j-coroutines:compileTestKotlin`,
  `BUILD SUCCESSFUL in 2s`.
- `/tmp/issue381-timelimiter-test.log`:
  `:spring-boot-resilience4j-coroutines:test --tests '*TimeLimiterTest*'`,
  `BUILD SUCCESSFUL in 20s`, 8 passing tests; BackendB Mono/Flux programmatic
  timeout tests each passed in 2s.
- `/tmp/issue381-reactive-circuitbreaker-test.log`:
  `:spring-boot-resilience4j-coroutines:test --tests '*ReactiveCircuitBreakerTest*'`,
  12 tests executed and 2 existing tests skipped.
- `/tmp/issue381-full-build.log`: repository post-work `./gradlew build`,
  `BUILD SUCCESSFUL in 2m 56s`.
- `/tmp/issue381-diff-check.log`: `git diff --check`, PASS with no output.

## Future Notes

For resilience examples, tests must verify the actual resilience effect, not just HTTP status. Timeout examples should assert fallback/error shape and elapsed time against the configured timeout or source delay.
