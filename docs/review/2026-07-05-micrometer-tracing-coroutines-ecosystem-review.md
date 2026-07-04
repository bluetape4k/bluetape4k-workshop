# micrometer-tracing-coroutines Ecosystem Review

Date: 2026-07-05
Module: `:micrometer-tracing-coroutines`
Branch: `refactor/micrometer-tracing-coroutines-ecosystem-patterns`

## Scope

- Declare direct `bluetape4k-core` dependency for support helper usage.
- Validate sync todo ids with bluetape4k `requirePositiveNumber`.
- Keep the deliberate sync-boundary blocking demonstration while naming the simulated blocking work.

## 7-Tier Review

| Tier | Lens | Verdict | Evidence |
|---|---|---|---|
| 1 | Security/input | PASS | Controller and service reject non-positive todo ids before logging, WebClient calls, or observation spans. |
| 2 | Architecture | PASS | Sync/coroutine comparison shape, controller/service split, and tracing application wiring remain unchanged. |
| 3 | Coroutines/tracing | PASS | `runBlocking(Dispatchers.VT)` remains only at the sync boundary; coroutine service path was not changed. |
| 4 | Code quality | PASS | Repeated sleep literal was centralized behind `simulateBlockingWork`; touched Kotlin spacing was normalized. |
| 5 | Tests | PASS | `./gradlew :micrometer-tracing-coroutines:test --console=plain --max-workers=1` passed. |
| 6 | Operations | PASS | Zipkin launcher/test behavior and runtime configuration remain unchanged. |
| 7 | Evidence/docs | PASS | `git diff --check` passed; Gradle test output executed 11 tests with 1 intentional skip. |

## P0/P1 Gate

- P0: 0
- P1: 0
- Deferred: commented teaching snippets still mention `Thread.sleep` and `runBlocking`; active blocking calls are named as simulated sync work.
