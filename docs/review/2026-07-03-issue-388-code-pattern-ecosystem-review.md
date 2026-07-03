# Issue #388 Code Pattern Ecosystem Review

Date: 2026-07-03
Scope: repository-wide Kotlin pattern scan, with safe fixes in 11 workshop modules

## Summary

This review checks the residual ecosystem-use drift found after the milestone 1.3.1 examples and the broader follow-up scan requested after the initial PR was too narrow:

- Replace opaque string ID generation in `OrderId.newId()` with `Base58.randomString(8)`.
- Replace `kotlin.test.assertFailsWith` with `io.bluetape4k.assertions.assertFailsWith`.
- Prefer `bluetape4k.support` validation helpers in touched domain value objects.
- Remove production `!!` from the scanned source tree by replacing it with `requireNotNull`, `requireNotBlank`, or `checkNotNull` according to caller-input vs invariant intent.
- Replace a runtime `println` with bluetape4k lazy logging.
- Remove a stale commented projection example that still taught `!!`.

## Repository Pattern Scan

The wide scan covered 1,473 tracked Kotlin files.

| Pattern | Total | `src/main` | Disposition |
|---|---:|---:|---|
| `!!` | 176 | 0 | Production code fixed; remaining matches are tests/examples and are tracked by #392. |
| Raw `require(...)` | 155 | 151 | Broad domain-validation cleanup remains; many are constructor/domain invariant sites and need module-by-module intent checks in #390. |
| Raw `check(...)` | 33 | 15 | Mostly invariant checks; no mechanical replacement. |
| `Thread.sleep(...)` | 113 | 31 | Many are virtual-thread/blocking demonstration examples; production/service sleeps are tracked by #391. |
| `runBlocking { ... }` | 6 | 6 | All production matches are blocking-to-suspend bridge examples; cancellation/dispatcher review is tracked by #391. |
| Raw `println(...)` | 12 | 7 | Runtime usage fixed; remaining production matches are KDoc examples. |
| Raw `GenericContainer` | 0 | 0 | No violation found. |
| Direct `XxxServer(...)` constructor | 7 | 1 | Main match is covered by issue #382 / PR #387; test matches are documented custom-container exceptions. |
| Legacy assertion imports | 0 | 0 | No `kotlin.test`, JUnit assertion, AssertJ, or Kluent imports remain. |
| `UUID.randomUUID()` | 0 | 0 | No remaining UUID generation drift. |

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| 1 Security | PASS | The generated order identifier remains an opaque synthetic example id and does not include PII. |
| 2 Correctness | PASS | `OrderId.newId()` still produces non-blank `order-*` ids; validation keeps caller-input failures as `IllegalArgumentException`, while persisted-id/observation/singleton assumptions now fail as explicit invariants. |
| 3 Architecture | PASS | No module boundaries or public workflows changed; the diff only swaps raw JDK/test helpers and unsafe null assertions for bluetape4k ecosystem helpers or explicit Kotlin invariants. |
| 4 Code Quality | PASS | `Base58.randomString`, `requireNotBlank`, `requireNotEmpty`, `requireNotNull`, `requirePositiveNumber`, `requireZeroOrPositiveNumber`, `checkNotNull`, lazy logging, and `bluetape4k.assertions.assertFailsWith` are used in the touched code. |
| 5 Tests | PASS | Compile and tests pass for all touched modules plus the original issue modules. |
| 6 Docs/Examples | PASS | No README behavior changed; review and lesson artifacts capture the ecosystem rule and exceptions. |
| 7 Evidence | PASS | Pattern grep, compile, tests, and `git diff --check` were run on the feature branch. |

P0/P1 findings: 0.

## Exceptions Checked

- `leader/leader-election/src/test/.../RedisFailureTest.kt` keeps a dedicated `RedisServer` because the test intentionally stops Redis and must not stop the shared launcher singleton.
- `spring-boot/cache-resilience/src/test/.../ResilientCacheServiceTest.kt` keeps a dedicated `RedisServer` because the Redis container must join the same Docker network as Toxiproxy with the `redis` alias.
- `spring-data/elasticsearch*/src/test/.../ElasticsearchServerTest.kt` intentionally constructs a server with a non-default password to verify server wrapper behavior.
- `ratelimit/bucker4j-bluetape4k-webflux/.../TestRedisConfig.kt` is handled by the separate issue #382 / PR #387 branch.
- Production `Thread.sleep` and `runBlocking` matches were not changed mechanically because several modules are explicit blocking, virtual-thread, or leader-bridge examples. They need behavior-preserving module issues.
- Follow-up issues created from this pass: #390 raw validation helpers, #391 blocking/sleep boundaries, #392 test null assertions.

## Verification

- `rg -n "UUID\\.randomUUID\\(\\)|import java\\.util\\.UUID|import kotlin\\.test|kotlin\\.test\\.assertFailsWith|assertThrows\\(|org\\.junit\\.jupiter\\.api\\.Assertions" spring-modulith/ddd-order-audit kotlin/flow-extensions-parallel-enrichment -g '*.kt'` returned no matches.
- Repository scan after fixes: production `!!` = 0, raw `GenericContainer` = 0, legacy assertion imports = 0, `UUID.randomUUID()` = 0.
- `./gradlew :spring-modulith-ddd-order-audit:compileKotlin :spring-modulith-ddd-order-audit:compileTestKotlin :kotlin-flow-extensions-parallel-enrichment:compileKotlin :kotlin-flow-extensions-parallel-enrichment:compileTestKotlin :spring-data-jpa-querydsl:compileKotlin :spring-data-jpa-querydsl:compileTestKotlin :spring-data-redis-examples:compileKotlin :spring-data-redis-examples:compileTestKotlin :spring-data-elasticsearch-webflux:compileKotlin :spring-data-elasticsearch-webflux:compileTestKotlin :spring-data-mongodb-transactions:compileKotlin :spring-data-mongodb-transactions:compileTestKotlin :exposed-webflux-r2dbc:compileKotlin :exposed-webflux-r2dbc:compileTestKotlin :bucket4j-redis:compileKotlin :bucket4j-redis:compileTestKotlin :spring-modulith-jpa-demo:compileKotlin :spring-modulith-jpa-demo:compileTestKotlin :micrometer-observation:compileKotlin :micrometer-observation:compileTestKotlin :kotlin-design-patterns:compileKotlin :kotlin-design-patterns:compileTestKotlin --warning-mode all --max-workers=1 --console=plain` passed.
- `./gradlew :spring-modulith-ddd-order-audit:test :kotlin-flow-extensions-parallel-enrichment:test :spring-data-jpa-querydsl:test :spring-data-redis-examples:test :spring-data-elasticsearch-webflux:test :spring-data-mongodb-transactions:test :exposed-webflux-r2dbc:test :bucket4j-redis:test :spring-modulith-jpa-demo:test :micrometer-observation:test :kotlin-design-patterns:test --max-workers=1 --console=plain` passed.
- `git diff --check` passed.

Residual risks:

- The Spring Modulith test run logs PostgreSQL/JPA shutdown warnings after successful tests; this is existing container lifecycle noise and not introduced by the code-pattern refactor.
- Test sources still contain many `!!` and `Thread.sleep` usages. These are visible debt and should be handled as separate focused issues because several tests need assertion-intent and Awaitility/coroutine-helper rewrites rather than mechanical replacement.
