# Issue 392 Test Null Assertion Review

## 범위

- 이슈: #392 `Clean up test null assertions with bluetape4k assertion patterns`
- 작업 유형: Type B fast-track test refactor.
- Diff 범위: Exposed WebFlux R2DBC, Jackson, messaging outbox, Spring Boot examples, Spring Data, Redis, MongoDB, legacy Spring Cloud example에 걸친 21 test files.
- CodeReviewGraph: 이 worktree에서는 사용할 수 없었으므로 direct diff/source scan, targeted compile, affected-module tests, full local build를 사용했다.

## Scan Evidence

- comment와 string-only `!!!`를 filtering한 뒤 baseline Kotlin `!!` candidate: `163`.
- refactor 후: `68`.
- 감소: `95`.
- 변경 files: `21`.
- 남은 high-count exception:
  - `io/okio-examples/.../BufferCursorTest.kt`: cursor buffer nullability와 offset access가 test subject이므로, 이후 focused Okio rewrite는 mechanical assertion chain 대신 local buffer helper를 도입해야 한다.
  - `kotlin/coroutines/.../SpringCoroutineScope.kt`: coroutine `Job` lookup은 test support class의 framework wiring이며 coroutine-scope semantic과 함께 검토해야 한다.
  - `spring-cloud/gateway-example` 같은 legacy/unregistered module은 여기에서 source-review했지만, `settings.gradle.kts`가 Spring Cloud example을 제외하므로 root Gradle build에는 포함되지 않는다.

## 7-Tier Review

| Tier | 판정 | 근거 |
|---|---|---|
| Security | PASS | production behavior나 input validation은 변경되지 않았다. failure mode는 test에서만 NPE에서 assertion failure로 이동했다. |
| Stability | PASS | Nullable response body, repository lookup result, Querydsl result, Testcontainers property는 이제 명시적 `shouldNotBeNull()` assertion으로 실패한다. |
| Performance | PASS | test-only assertion call이 NPE-forcing operator를 대체한다. production hot path는 변경되지 않았다. |
| Operator/Ops | PASS | Testcontainers-backed affected test는 `--max-workers=1`로 serial 실행했다. container launcher나 workflow 변경은 없다. |
| Developer/API | PASS | refactor는 Kotlin `!!` 대신 bluetape4k assertion API를 사용하여 test intent를 읽기 쉽게 보존한다. |
| User/Caller | PASS | Public example behavior와 HTTP contract는 변경되지 않았다. test assertion shape만 변경되었다. |
| Evidence | PASS | Affected compile, affected test, post-work full build가 통과했다. `spring-cloud/gateway-example`은 documented excluded-module source-review case로 남는다. |

## 검증 근거

- clean `develop`에서 작업 전 local build: `./gradlew build --max-workers=1 --console=plain` -> `BUILD SUCCESSFUL in 1m 40s`.
- Affected compile round 1: registered initial modules -> `BUILD SUCCESSFUL in 9s`.
- Affected compile round 2: Exposed WebFlux and Elasticsearch additions -> `BUILD SUCCESSFUL in 4s`.
- Querydsl compile: `:spring-data-jpa-querydsl:compileTestKotlin` -> `BUILD SUCCESSFUL in 4s`.
- Redis/Mongo compile: `:spring-data-redis-examples:compileTestKotlin :spring-data-mongodb-coroutines:compileTestKotlin` -> `BUILD SUCCESSFUL in 3s`.
- Affected-module tests: 11 registered modules -> `BUILD SUCCESSFUL in 1m 33s`.
- Diff hygiene: `git diff --check` -> PASS.
- Post-work full local build: `./gradlew build --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 2m 15s`.

## 발견사항

- P0/P1: 0.
- P2: 남은 `!!` case는 Okio cursor buffer access와 일부 legacy/framework-bound example에 집중되어 있다. 넓은 mechanical rewrite로 숨기지 말고 focused follow-up work에서 다뤄야 한다.
- P3: 일부 수정된 import는 기존부터 non-standard order였다. 이 PR은 broad formatting churn을 실행하지 않는다.
