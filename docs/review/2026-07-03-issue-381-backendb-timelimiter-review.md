# Issue #381 BackendB TimeLimiter Review

날짜: 2026-07-03
범위: Issue #381, BackendB programmatic Reactor TimeLimiter coverage.

## Reviewed Diff

- `spring-boot/resilience4j-coroutines/src/main/kotlin/io/bluetape4k/workshop/resilience/controller/BackendBController.kt`
- `spring-boot/resilience4j-coroutines/src/test/kotlin/io/bluetape4k/workshop/resilience/timelimiter/TimeLimiterTest.kt`

## 7-Tier Findings

| Tier | Lens | 판정 | 근거 |
|---|---|---|---|
| 1 | Security | PASS | authentication, authorization, user input parsing, serialization, secret-handling behavior는 변경되지 않았다. |
| 2 | Architecture | PASS | fix는 기존 BackendB programmatic Resilience4j controller 안에 머물며 이미 설정된 `TimeLimiterRegistry` instance를 재사용한다. module boundary나 dependency 변경은 없다. |
| 3 | Concurrency / Lifecycle | PASS | Reactor timeout은 Resilience4j `TimeLimiterOperator`를 사용하며 thread나 blocking work를 추가하지 않는다. scheduler lifecycle cleanup은 issue #383을 통해 현재 base에 이미 존재하며, 이 diff는 그것을 다시 손대지 않는다. |
| 4 | Code Quality / Correctness | PASS | `TimeLimiterOperator`는 `Mono`와 `Flux` fallback endpoint의 retry/circuit-breaker/bulkhead pipeline을 감싸므로 3초 source delay가 설정된 2초 limiter에 의해 cutoff된다. |
| 5 | Tests | PASS | BackendB config와 programmatic Mono/Flux timeout test를 추가했다. 새 테스트는 fallback body와 3초 slow source delay보다 짧은 elapsed time을 assert한다. |
| 6 | Performance / Operations | PASS | BackendB slow Reactor timeout path는 이제 전체 delayed publisher를 기다리지 않고 약 2초에 반환한다. 추가 IO나 external service requirement는 없다. |
| 7 | Documentation / Evidence | PASS | Public README/API 문서는 변경되지 않았다. 근거는 이 review artifact와 targeted Gradle output에 기록했다. |

## P0/P1 Gate

- P0: 0
- P1: 0
- P2/P3: 없음.

## 검토 메모

이 변경의 핵심은 BackendB의 Reactor fallback path가 설정된 TimeLimiter 계약을 실제로 따르는지 확인하는 것이다. review는 endpoint behavior가 느린 publisher 전체 지연을 기다리지 않고 제한 시간 이후 fallback body를 반환한다는 점과, lifecycle 관련 변경은 별도 #383 기반에 머문다는 점을 분리해 검증했다.

## 검증 근거

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
