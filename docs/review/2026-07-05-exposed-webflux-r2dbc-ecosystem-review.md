# Exposed WebFlux R2DBC Ecosystem Review

Date: 2026-07-05
Module: `:exposed-webflux-r2dbc`

## Scope

7-Tier review and remediation for Kotlin style, bluetape4k ecosystem alignment,
Spring WebFlux validation, Exposed R2DBC transaction behavior, coroutine
concurrency tests, and example-module parity with the MVC data-access modules.

## Findings Closed

| Finding | Resolution |
| --- | --- |
| Product/order validation lagged MVC parity | Added `@NotBlank`, `@Positive`, `@Min(0)`, and `@Size(max = 100)` constraints to request DTOs. |
| Book author id validation missing | Added `@Positive` to `CreateBookRequest.authorId`. |
| Book create/update relied on FK failure for missing author | `BookService` now checks `AuthorRepository.findByIdOrNull()` before create/update. |
| Coroutine concurrency test masked failures | Replaced ad hoc `async`/`runCatching` loops with bluetape4k `SuspendedJobTester`. |
| Stock invariant under-asserted | Test now asserts exact success/conflict counts and final stock `0`. |
| Insufficient-stock regression accepted any 4xx | Test now asserts exact `409 CONFLICT`. |
| Touched tests used `!!` | Replaced with `shouldNotBeNull()` captures. |

## Ecosystem Usage

| Area | Evidence |
| --- | --- |
| bluetape4k assertions | Touched tests use `shouldNotBeNull()` and direct value matchers. |
| bluetape4k coroutine helper | Concurrency tests use `SuspendedJobTester`; `rounds(concurrency)` defines the total attempts. |
| Exposed R2DBC | Author precheck stays inside the same `suspendTransaction` as book writes. |

## 7-Tier Verdict

| Tier | Verdict | Evidence |
| --- | --- | --- |
| Spec / scope | PASS | Diff is limited to `:exposed-webflux-r2dbc` validation, service precheck, and tests. |
| API validation | PASS | Product/order/book invalid input regressions return controlled 400/404/409 outcomes. |
| Data correctness | PASS | Missing author is checked before book create/update. |
| Coroutine / R2DBC style | PASS | `SuspendedJobTester` replaces ad hoc coroutine stress loops; no `runCatching` masking remains. |
| Tests | PASS | Targeted regressions added; exact stock invariant verified. |
| Security / error handling | PASS | FK failures no longer define the public example contract. |
| Build / static validation | PASS | Targeted Gradle compile/test and `data-access-full` smoke pass. |

## Verification

- `git diff --check`: PASS.
- Static scan for `.shouldBeEqualTo(`, boolean equality assertions, `assertThrows`,
  `kotlin.test`, `SqlExpressionBuilder`, `runCatching`, `!!`, `Executors`,
  `CountDownLatch`, `Thread.sleep`, `async(`, and `coroutineScope`: PASS, no output.
- `repo-test-summary -- ./gradlew :exposed-webflux-r2dbc:compileKotlin :exposed-webflux-r2dbc:compileTestKotlin :exposed-webflux-r2dbc:cleanTest :exposed-webflux-r2dbc:test --no-build-cache --warning-mode all --console=plain --max-workers=1`: PASS, 15 tests.
- `MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh data-access-full`: PASS, Gradle `BUILD SUCCESSFUL`.
- `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`: PASS, 101 active modules, no stale README refs, no broken README image links.
- Native code-reviewer re-review: APPROVE, P0/P1/P2/P3 = 0.
