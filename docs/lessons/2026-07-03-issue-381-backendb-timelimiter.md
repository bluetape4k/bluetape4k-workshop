# Issue 381 BackendB TimeLimiter Coverage

## 배경

Issue #381은 `spring-boot/resilience4j-coroutines` workshop example의 gap을 수정한다.
BackendB는 `TimeLimiterRegistry`를 연결했지만, programmatic Reactor `Mono`와 `Flux`
fallback chain에는 Reactor TimeLimiter operator를 적용하지 않았다. 기존 circuit-breaker
test는 2xx response만 assertion했기 때문에, 느린 publisher가 완료되어도 설정된 2초 timeout을
증명하지 못했다.

## 결정

- 수정은 `BackendBController`에 유지하고, 기존 `TimeLimiterRegistry`의 `timeLimiter`를
  재사용한다.
- fallback timeout endpoint에서는 retry/circuit-breaker/bulkhead Reactor chain 바깥에
  `TimeLimiterOperator`를 적용해 느린 reactive pipeline 전체가 설정된 timeout에 묶이게 한다.
- timeout behavior는 circuit-breaker state test보다 `TimeLimiterTest`가 더 잘 소유하므로
  BackendB-specific test를 `TimeLimiterTest`에 추가한다.
- fallback response content와 3초 source delay보다 짧은 elapsed time을 모두 assertion한다.
  timeout example에서는 status-only check가 충분하지 않다.
- 이 bug fix는 Reactor `TimeLimiterOperator` wiring으로 범위를 제한한다. scheduler lifecycle
  cleanup은 현재 base에 issue #383으로 이미 있으므로, 이 변경에서 다시 작업하지 않는다.

## 결과

BackendB programmatic `monoTimeout`과 `fluxTimeout`은 이제 전체 delayed publisher를 기다리지
않고, 설정된 2초 TimeLimiter window 근처에서 fallback response를 반환한다.

## 검증

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

## 향후 참고

resilience example의 test는 HTTP status뿐 아니라 실제 resilience effect를 검증해야 한다.
timeout example은 설정된 timeout 또는 source delay에 대해 fallback/error shape와 elapsed
time을 assertion해야 한다.
