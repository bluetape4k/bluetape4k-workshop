# bucker4j-bluetape4k-webflux Ecosystem Review

Date: 2026-07-05
Module: `:bucker4j-bluetape4k-webflux`
Branch: `refactor/bucker4j-bluetape4k-webflux-ecosystem-patterns`

## Scope

- Declare direct `bluetape4k-core` dependency for support helper usage.
- Validate resolved rate-limit keys with bluetape4k `requireNotBlank`.
- Preserve Bucket4j Redis rate-limit behavior while rethrowing coroutine cancellation before fallback.

## 7-Tier Review

| Tier | Lens | Verdict | Evidence |
|---|---|---|---|
| 1 | Security/input | PASS | Header/IP-derived rate-limit keys are explicitly guarded before token consumption. |
| 2 | Architecture | PASS | WebFilter ordering, Bucket4j proxy providers, Redis configuration, and controller endpoints remain unchanged. |
| 3 | Reactive/coroutines | PASS | Coroutine cancellation is rethrown in the async filter before broad fallback handling. |
| 4 | Code quality | PASS | Regex targets are reused instead of rebuilt per request; touched Kotlin spacing and public KDoc were normalized. |
| 5 | Tests | PASS | `./gradlew :bucker4j-bluetape4k-webflux:test --console=plain --max-workers=1` passed. |
| 6 | Operations | PASS | Redis Testcontainers launcher and rate-limit token settings remain unchanged. |
| 7 | Evidence/docs | PASS | First compile exposed a KDoc `/**` token bug; after doc-string repair, `git diff --check` and module tests passed. |

## P0/P1 Gate

- P0: 0
- P1: 0
- Deferred: disabled IP-address rate-limit tests remain unchanged because they conflict across coroutine/reactive paths by design.
