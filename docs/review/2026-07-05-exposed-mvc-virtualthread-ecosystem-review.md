# Exposed MVC Virtual Thread 생태계 리뷰

날짜: 2026-07-05
모듈: `:exposed-mvc-virtualthread`

## 범위

Kotlin style, bluetape4k 생태계 정렬, Spring MVC validation, Exposed transaction behavior, virtual-thread test style,
`:exposed-mvc-jdbc`와의 example-module parity에 대한 7-Tier review와 remediation 결과다.

## 해결한 발견 사항

| 발견 사항 | 해결 |
| --- | --- |
| `@Email`만으로 blank email을 허용함 | `CreateAuthorRequest.email`은 이제 `@NotBlank @Email`을 요구하며 controller regression은 400을 반환한다. |
| whitespace product name을 허용함 | `CreateProductRequest.name`은 이제 `@NotBlank`를 사용하며 controller regression은 400을 반환한다. |
| book creation이 missing author에 대해 database FK failure에 의존함 | `AuthorService.createBook()`은 insert 전에 `authorRepo.findById()`를 확인하고 기존 404 path를 반환한다. |
| raw executor/latch concurrency test | bluetape4k `MultithreadingTester`로 대체했다. |
| 수정된 test가 `!!`를 사용함 | 대신 `shouldNotBeNull()` 값을 capture했다. |
| delete path의 Kotlin compile warning | `deleteById()`는 unused expression 없이 transaction을 `Unit`으로 끝낸다. |

## 생태계 사용

| 영역 | 근거 |
| --- | --- |
| bluetape4k assertions | 수정된 test는 `shouldNotBeNull()`와 value matcher를 사용한다. |
| bluetape4k concurrency helper | stock contention test는 `MultithreadingTester`를 사용한다. |
| Exposed transaction path | service-level author precheck는 low-level FK failure가 example API contract로 노출되는 일을 피한다. |

## 7-Tier 판정

| Tier | 판정 | 근거 |
| --- | --- | --- |
| Spec / scope | PASS | diff는 `:exposed-mvc-virtualthread` validation, service precheck, test로 제한된다. |
| API validation | PASS | blank email/product name regression은 400을 반환한다. |
| Data correctness | PASS | missing author는 book insert 전에 확인한다. |
| Concurrency | PASS | `MultithreadingTester`가 ad hoc executor/latch를 대체한다. |
| Tests | PASS | targeted regression을 추가했고 forbidden-pattern scan은 clean이다. |
| Security / error handling | PASS | missing author는 DB integrity leakage 대신 explicit not-found handling을 따른다. |
| Build / static validation | PASS | targeted Gradle compile/test와 `data-access-full` smoke가 통과한다. |

## 검증

- `git diff --check`: PASS.
- `.shouldBeEqualTo(`, boolean equality assertion, `assertThrows`, `kotlin.test`, `SqlExpressionBuilder`, `runCatching`, `!!`,
  `CountDownLatch`, `Thread.sleep`에 대한 static scan: PASS, output 없음.
- `repo-test-summary -- ./gradlew :exposed-mvc-virtualthread:compileKotlin :exposed-mvc-virtualthread:compileTestKotlin :exposed-mvc-virtualthread:cleanTest :exposed-mvc-virtualthread:test --no-build-cache --warning-mode all --console=plain --max-workers=1`: PASS, 14개 test.
- `MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh data-access-full`: PASS, Gradle `BUILD SUCCESSFUL`.
- `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`: PASS, active module 101개, stale README ref 없음, 깨진 README image link 없음.
- Native code-reviewer 재리뷰: APPROVE, P0/P1/P2/P3 = 0.
