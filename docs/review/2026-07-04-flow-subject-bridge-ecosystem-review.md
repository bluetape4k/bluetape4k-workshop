# Flow Subject Bridge Ecosystem Review

Date: 2026-07-04
Scope: `kotlin/flow-extensions-subject-bridge`

## Summary

This review tightens the callback-to-Flow subject bridge example against the bluetape4k ecosystem code-pattern rules:

- Declare the direct `bluetape4k-core` dependency before using validation helpers.
- Validate subject sizing boundaries with `requirePositiveNumber`.
- Preserve the existing Publish/Behavior/Replay/Multicast/Unicast subject teaching surface.
- Add a regression test for invalid bridge bounds.

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| 1 Security | PASS | The module remains an in-memory callback bridge example and adds no external I/O, secrets, or logging surface. |
| 2 Correctness | PASS | Invalid replay history size and multicast subscriber count now fail before Subject construction. |
| 3 Architecture | PASS | The bridge still exposes read-only `Flow` views and keeps Subject mutation isolated inside `DeviceSubjectBridge`. |
| 4 Code Quality | PASS | Public boundary validation uses `io.bluetape4k.support.requirePositiveNumber`; tests use bluetape4k assertions. |
| 5 Tests | PASS | `DeviceSubjectBridgeTest` covers subject behavior, completion/error propagation, unicast collector rules, and invalid bounds. |
| 6 Docs/Examples | PASS | README semantics remain accurate because only invalid constructor inputs changed. |
| 7 Evidence | PASS | Targeted Gradle test, pattern scan, and `git diff --check` passed in the module worktree. |

P0/P1 findings: 0.

## Verification

- `./gradlew :kotlin-flow-extensions-subject-bridge:test --console=plain` passed: 11 tests executed.
- `git diff --check` passed.
- `rg -n "!!|\brequire\(|Thread\.sleep|runBlocking|assertThrows|kotlin\.test|GenericContainer|println\(" kotlin/flow-extensions-subject-bridge -g '*.kt'` returned no matches.

