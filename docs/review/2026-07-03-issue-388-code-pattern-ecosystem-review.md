# Issue #388 Code Pattern Ecosystem Review

Date: 2026-07-03
Scope: `spring-modulith-ddd-order-audit`, `kotlin-flow-extensions-parallel-enrichment`

## Summary

This review checks the residual ecosystem-use drift found after the milestone 1.3.1 examples:

- Replace opaque string ID generation in `OrderId.newId()` with `Base58.randomString(8)`.
- Replace `kotlin.test.assertFailsWith` with `io.bluetape4k.assertions.assertFailsWith`.
- Prefer `bluetape4k.support` validation helpers in touched domain value objects.

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| 1 Security | PASS | The generated order identifier remains an opaque synthetic example id and does not include PII. |
| 2 Correctness | PASS | `OrderId.newId()` still produces non-blank `order-*` ids; domain validation still throws `IllegalArgumentException` for caller input. |
| 3 Architecture | PASS | No module boundaries or public workflows changed; the diff only swaps raw JDK/test helpers for bluetape4k ecosystem helpers. |
| 4 Code Quality | PASS | `Base58.randomString`, `requireNotBlank`, `requireNotEmpty`, `requirePositiveNumber`, `requireZeroOrPositiveNumber`, and `bluetape4k.assertions.assertFailsWith` are used in the touched code. |
| 5 Tests | PASS | Targeted compile and tests pass for both affected modules. |
| 6 Docs/Examples | PASS | No README behavior changed; review and lesson artifacts capture the ecosystem rule and exceptions. |
| 7 Evidence | PASS | Pattern grep, compile, tests, and `git diff --check` were run on the feature branch. |

P0/P1 findings: 0.

## Exceptions Checked

- `leader/leader-election/src/test/.../RedisFailureTest.kt` keeps a dedicated `RedisServer` because the test intentionally stops Redis and must not stop the shared launcher singleton.
- `spring-boot/cache-resilience/src/test/.../ResilientCacheServiceTest.kt` keeps a dedicated `RedisServer` because the Redis container must join the same Docker network as Toxiproxy with the `redis` alias.
- `ratelimit/bucker4j-bluetape4k-webflux/.../TestRedisConfig.kt` is handled by the separate issue #382 / PR #387 branch.

## Verification

- `rg -n "UUID\\.randomUUID\\(\\)|import java\\.util\\.UUID|import kotlin\\.test|kotlin\\.test\\.assertFailsWith|assertThrows\\(|org\\.junit\\.jupiter\\.api\\.Assertions" spring-modulith/ddd-order-audit kotlin/flow-extensions-parallel-enrichment -g '*.kt'` returned no matches.
- `./gradlew :spring-modulith-ddd-order-audit:compileKotlin :spring-modulith-ddd-order-audit:compileTestKotlin :kotlin-flow-extensions-parallel-enrichment:compileKotlin :kotlin-flow-extensions-parallel-enrichment:compileTestKotlin --warning-mode all --max-workers=1 --console=plain` passed.
- `./gradlew :spring-modulith-ddd-order-audit:test :kotlin-flow-extensions-parallel-enrichment:test --max-workers=1 --console=plain` passed with 15 Spring Modulith tests and 6 Flow tests.
- `git diff --check` passed.

Residual risk: the Spring Modulith test run logs PostgreSQL/JPA shutdown warnings after successful tests; this is existing container lifecycle noise and not introduced by the code-pattern refactor.
