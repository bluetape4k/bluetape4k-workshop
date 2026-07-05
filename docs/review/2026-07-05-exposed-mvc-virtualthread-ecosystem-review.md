# Exposed MVC Virtual Thread Ecosystem Review

Date: 2026-07-05
Module: `:exposed-mvc-virtualthread`

## Scope

7-Tier review and remediation for Kotlin style, bluetape4k ecosystem alignment,
Spring MVC validation, Exposed transaction behavior, virtual-thread test style,
and example-module parity with `:exposed-mvc-jdbc`.

## Findings Closed

| Finding | Resolution |
| --- | --- |
| Blank email accepted by `@Email` alone | `CreateAuthorRequest.email` now requires `@NotBlank @Email`; controller regression returns 400. |
| Whitespace product names accepted | `CreateProductRequest.name` now uses `@NotBlank`; controller regression returns 400. |
| Book creation relied on database FK failure for missing author | `AuthorService.createBook()` checks `authorRepo.findById()` before insert and returns the existing 404 path. |
| Raw executor/latch concurrency test | Replaced with bluetape4k `MultithreadingTester`. |
| Touched tests used `!!` | Captured `shouldNotBeNull()` values instead. |
| Kotlin compile warning in delete path | `deleteById()` now exits the transaction as `Unit` without an unused expression. |

## Ecosystem Usage

| Area | Evidence |
| --- | --- |
| bluetape4k assertions | Touched tests use `shouldNotBeNull()` and value matchers. |
| bluetape4k concurrency helper | Stock contention test uses `MultithreadingTester`. |
| Exposed transaction path | Service-level author precheck avoids surfacing a low-level FK failure as an example API contract. |

## 7-Tier Verdict

| Tier | Verdict | Evidence |
| --- | --- | --- |
| Spec / scope | PASS | Diff is limited to `:exposed-mvc-virtualthread` validation, service precheck, and tests. |
| API validation | PASS | Blank email/product name regressions return 400. |
| Data correctness | PASS | Missing author is checked before book insert. |
| Concurrency | PASS | `MultithreadingTester` replaces ad hoc executor/latch. |
| Tests | PASS | Targeted regressions added; forbidden-pattern scan is clean. |
| Security / error handling | PASS | Missing author follows explicit not-found handling instead of DB integrity leakage. |
| Build / static validation | PASS | Targeted Gradle compile/test and `data-access-full` smoke pass. |

## Verification

- `git diff --check`: PASS.
- Static scan for `.shouldBeEqualTo(`, boolean equality assertions, `assertThrows`,
  `kotlin.test`, `SqlExpressionBuilder`, `runCatching`, `!!`,
  `CountDownLatch`, and `Thread.sleep`: PASS, no output.
- `repo-test-summary -- ./gradlew :exposed-mvc-virtualthread:compileKotlin :exposed-mvc-virtualthread:compileTestKotlin :exposed-mvc-virtualthread:cleanTest :exposed-mvc-virtualthread:test --no-build-cache --warning-mode all --console=plain --max-workers=1`: PASS, 14 tests.
- `MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh data-access-full`: PASS, Gradle `BUILD SUCCESSFUL`.
- `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`: PASS, 101 active modules, no stale README refs, no broken README image links.
- Native code-reviewer re-review: APPROVE, P0/P1/P2/P3 = 0.
