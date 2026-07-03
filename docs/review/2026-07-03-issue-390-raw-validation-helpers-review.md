# Issue 390 Raw Validation Helper Review

## Scope

- Issue: #390 `Refactor raw validation to bluetape4k helpers`
- Work type: Type B fast-track refactor with broad validation cleanup.
- Diff scope: 19 files across AWS, Exposed, image-processing, Kotlin Flow, Ktor, leader, messaging, and Spring Modulith examples.
- CodeReviewGraph: unavailable in this worktree (`Files: 0`, `Last updated: never`), so review used direct diff, source scan, compile, and tests.

## Scan Evidence

- Baseline before this issue work: `src/main` raw `require(...)` occurrences `151` in `36` files.
- After refactor: `src/main` raw `require(...)` occurrences `111` in `32` files.
- Changed production files reduced raw validation from `92` to `52` occurrences.
- Remaining raw `require(...)` instances are intentionally kept where the predicate is not a simple caller-input helper case:
  - regex, content-type, and security predicates,
  - decoded image/parser boundary checks,
  - domain invariants such as state/event identity and stock availability,
  - exact decimal comparisons such as `BigDecimal` amount checks,
  - Okio `require(byteCount)` API calls,
  - error messages that must not echo sensitive caller input.

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| Security | PASS | Redaction blank text helper conversion was rejected because helper messages echo raw blank input; final diff keeps the non-echoing `blank-text` path outside helper conversion. |
| Stability | PASS | `compileKotlin` for 14 affected modules passed after fixing ByteArray helper misuse and adding direct `bluetape4k-core` dependencies for modules with production `io.bluetape4k.support` imports. |
| Performance | PASS | Validation helpers are inline/simple checks; no hot-loop allocations or blocking/runtime behavior changed. |
| Operator/Ops | PASS | No workflow/container/runtime configuration changed; Testcontainers-backed affected tests ran in a single Gradle invocation with `--max-workers=1`. |
| Developer/API | PASS | Production helper imports now have explicit `implementation(libs.bluetape4k.core)` boundaries; test assertion was loosened only from old exact wording to helper semantic wording. |
| User/Caller | PASS | OCR controller preserves the exact oversize HTTP detail expected by tests; helper conversion is limited where user-facing message contracts matter. |
| Evidence | PASS | `git diff --check`, affected-module compile/tests, and post-work full local build passed. |

## Validation Evidence

- Pre-work local build on clean `develop`: `./gradlew build --max-workers=1 --console=plain` -> `BUILD SUCCESSFUL in 9m 21s`.
- Affected compile: `./gradlew :aws-s3-vectors-access-grants:compileKotlin ... :spring-modulith-module-boundaries:compileKotlin --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 5s`.
- Affected tests: `./gradlew :aws-s3-vectors-access-grants:test ... :spring-modulith-module-boundaries:test --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 9s`.
- Post-work full local build after review fixes: `./gradlew build --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 1m 56s`.
- Diff hygiene: `git diff --check` -> PASS.

## Findings

- P0/P1: 0.
- P1 repair evidence: `Order.totalAmount` uses exact `BigDecimal.ZERO` comparison, not numeric helper conversion; direct `bluetape4k-core` dependencies were added where production support helpers are imported instead of relying on transitive dependencies.
- P2: Existing repository-wide Gradle deprecation warnings remain outside this issue scope.
- P3: Future cleanup can target remaining simple raw predicates in modules that need additional dependency-boundary decisions, but security/parser/domain predicates should stay explicit.
