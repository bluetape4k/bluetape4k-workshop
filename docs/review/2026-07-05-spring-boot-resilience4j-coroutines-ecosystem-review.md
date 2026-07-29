# spring-boot-resilience4j-coroutines 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-boot-resilience4j-coroutines`
브랜치: `refactor/spring-boot-resilience4j-coroutines-ecosystem-patterns`

## 범위

이 리뷰와 정리는 Resilience4j coroutines workshop 모듈의 Kotlin logging 스타일, coroutine-aware blocking simulation 정리, resilience 예제 안정성에 초점을 맞췄다.

## 검토한 변경

- 예제 `Thread.sleep(...)` 호출을 `LockSupport.parkNanos` 기반 `simulateBlockingLatency(...)`로 바꿨다.
- latency 값을 Kotlin `Duration` literal로 표현했다.
- coroutine-heavy logging은 `KLoggingChannel`에 유지하고 모듈 전체의 companion object 공백을 정규화했다.
- 기존 non-suspend close/test `runCatching` 사용은 suspend 호출이나 lifecycle cancellation path를 감싸지 않으므로 변경하지 않았다.

## 근거

- `repo-status`: staging 전 feature worktree에서 tracked 변경 경로 28개와 새 review/source 후보 경로 1개를 확인했다.
- `repo-diff`: review artifact 생성 전 tracked 28개 파일, 43 insertions, 35 deletions를 확인했다.
- CodeGraph `detect_changes_tool`: 변경 파일 28개를 분석했다. 이 workshop 모듈에서는 function/class node나 affected flow를 제공하지 못해 source diff와 타깃 Gradle 근거로 대체 검토했다.
- hard-smell scan: 모듈에서 `Thread.sleep`, `!!`, `companion object:`, raw JUnit assertion, kotlin.test assertion이 발견되지 않았다.
- `git diff --check`: PASS.
- `repo-test-summary -- ./gradlew :spring-boot-resilience4j-coroutines:test --console=plain --max-workers=1`: PASS, `SUCCESS: Executed 82 tests in 1m 20s (6 skipped)`, `BUILD SUCCESSFUL in 1m 28s`.

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
|---|---|---|
| Tier 1 - Security | PASS | 새 user input, auth, SQL, secret, external trust-boundary 동작은 없다. |
| Tier 2 - Architecture | PASS | latency simulation은 example 모듈 내부에 머물며 Resilience4j controller/service 계약을 바꾸지 않는다. |
| Tier 3 - API/Docs | PASS | 공개 example endpoint 계약이나 README-facing 동작은 변경하지 않았다. |
| Tier 4 - Correctness | PASS | timeout/fallback 동작은 기존 모듈 test suite가 계속 커버한다. |
| Tier 5 - 테스트 | PASS | 타깃 모듈 suite가 circuit breaker, retry, timelimiter, bulkhead, reactive, future, coroutine 예제를 커버한다. |
| Tier 6 - Performance/Stability | PASS | 직접 `Thread.sleep` 호출을 example code에서 제거했고 blocking latency는 명시적으로 격리했다. |
| Tier 7 - Evidence/Release | PASS | review artifact, hard-smell scan, diff check, 타깃 모듈 테스트 근거를 기록했다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- P2/P3: 없음

## 메모

latency helper는 Resilience4j time limiter 아래의 blocking backend 동작을 모델링하므로 이 예제 내부에 의도적으로 둔다. ad hoc concurrency test helper는 추가하지 않았다.
