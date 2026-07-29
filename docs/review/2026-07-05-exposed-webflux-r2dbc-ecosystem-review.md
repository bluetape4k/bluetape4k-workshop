# Exposed WebFlux R2DBC 생태계 리뷰

날짜: 2026-07-05
모듈: `:exposed-webflux-r2dbc`

## 범위

Kotlin style, bluetape4k 생태계 정렬, Spring WebFlux validation, Exposed R2DBC transaction behavior,
coroutine concurrency test, MVC data-access module과의 example-module parity에 대한 7-Tier review와 remediation 결과다.

## 해결한 발견 사항

| 발견 사항 | 해결 |
| --- | --- |
| product/order validation이 MVC parity보다 늦음 | request DTO에 `@NotBlank`, `@Positive`, `@Min(0)`, `@Size(max = 100)` constraint를 추가했다. |
| book author id validation 누락 | `CreateBookRequest.authorId`에 `@Positive`를 추가했다. |
| book create/update가 missing author에 대해 FK failure에 의존함 | `BookService`는 create/update 전에 `AuthorRepository.findByIdOrNull()`을 확인한다. |
| coroutine concurrency test가 failure를 masking함 | ad hoc `async`/`runCatching` loop를 bluetape4k `SuspendedJobTester`로 대체했다. |
| stock invariant assertion 부족 | test는 이제 정확한 success/conflict count와 final stock `0`을 assert한다. |
| insufficient-stock regression이 임의의 4xx를 허용함 | test는 이제 정확한 `409 CONFLICT`를 assert한다. |
| 수정된 test가 `!!`를 사용함 | `shouldNotBeNull()` capture로 대체했다. |

## 생태계 사용

| 영역 | 근거 |
| --- | --- |
| bluetape4k assertions | 수정된 test는 `shouldNotBeNull()`와 direct value matcher를 사용한다. |
| bluetape4k coroutine helper | concurrency test는 `SuspendedJobTester`를 사용하고, `rounds(concurrency)`가 전체 attempt 수를 정의한다. |
| Exposed R2DBC | author precheck는 book write와 같은 `suspendTransaction` 내부에 머문다. |

## 7-Tier 판정

| Tier | 판정 | 근거 |
| --- | --- | --- |
| Spec / scope | PASS | diff는 `:exposed-webflux-r2dbc` validation, service precheck, test로 제한된다. |
| API validation | PASS | product/order/book invalid input regression은 통제된 400/404/409 outcome을 반환한다. |
| Data correctness | PASS | missing author는 book create/update 전에 확인한다. |
| Coroutine / R2DBC style | PASS | `SuspendedJobTester`가 ad hoc coroutine stress loop를 대체하며, `runCatching` masking은 남아 있지 않다. |
| Tests | PASS | targeted regression을 추가했고 정확한 stock invariant를 검증했다. |
| Security / error handling | PASS | FK failure는 더 이상 public example contract를 정의하지 않는다. |
| Build / static validation | PASS | targeted Gradle compile/test와 `data-access-full` smoke가 통과한다. |

## 검증

- `git diff --check`: PASS.
- `.shouldBeEqualTo(`, boolean equality assertion, `assertThrows`, `kotlin.test`, `SqlExpressionBuilder`, `runCatching`,
  `!!`, `Executors`, `CountDownLatch`, `Thread.sleep`, `async(`, `coroutineScope`에 대한 static scan: PASS, output 없음.
- `repo-test-summary -- ./gradlew :exposed-webflux-r2dbc:compileKotlin :exposed-webflux-r2dbc:compileTestKotlin :exposed-webflux-r2dbc:cleanTest :exposed-webflux-r2dbc:test --no-build-cache --warning-mode all --console=plain --max-workers=1`: PASS, 15개 test.
- `MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh data-access-full`: PASS, Gradle `BUILD SUCCESSFUL`.
- `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`: PASS, active module 101개, stale README ref 없음, 깨진 README image link 없음.
- Native code-reviewer 재리뷰: APPROVE, P0/P1/P2/P3 = 0.
