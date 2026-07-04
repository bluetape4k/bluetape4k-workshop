# Flow Race Fallback Ecosystem Review

Date: 2026-07-04
Scope: `kotlin/flow-extensions-race-fallback`

## Summary

This review tightens the Flow source-composition example against the bluetape4k ecosystem code-pattern rules:

- Declare the direct `bluetape4k-core` dependency before using validation helpers.
- Validate source delays with `requireZeroOrPositiveNumber`.
- Reject empty source collections with `requireNotEmpty` before invoking composition operators.
- Preserve the existing `race`, `concat`, `concatArrayEager`, `concatMapEager`, `merge`, `materialize`, and `dematerialize` teaching surface.

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| 1 Security | PASS | The module remains an in-memory catalog source-composition example and adds no external endpoints, credentials, or logging surface. |
| 2 Correctness | PASS | Negative source delay and empty source collections now fail as caller input errors instead of relying on downstream operator behavior. |
| 3 Architecture | PASS | The catalog wrapper remains thin over bluetape4k Flow composition operators; no new orchestration layer was introduced. |
| 4 Code Quality | PASS | Boundary checks use `io.bluetape4k.support.requireZeroOrPositiveNumber` and `requireNotEmpty`; tests use bluetape4k assertions. |
| 5 Tests | PASS | `RaceFallbackCatalogTest` covers race cancellation, ordered/eager fallback, merge, materialize/dematerialize, invalid delay, and empty source inputs. |
| 6 Docs/Examples | PASS | README semantics remain accurate; invalid input behavior is now explicit but the operator examples are unchanged. |
| 7 Evidence | PASS | Targeted Gradle test and `git diff --check` passed in the module worktree. |

P0/P1 findings: 0.

## Verification

- `./gradlew :kotlin-flow-extensions-race-fallback:test --console=plain` passed: 9 tests executed.
- `git diff --check` passed.
- `rg "\brequire\(" kotlin/flow-extensions-race-fallback/src kotlin/flow-extensions-race-fallback/build.gradle.kts` returned no matches.

