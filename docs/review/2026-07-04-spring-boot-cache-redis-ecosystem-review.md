# spring-boot-cache-redis Ecosystem Code Review

Date: 2026-07-04
Module: `:spring-boot-cache-redis`
Branch: `refactor/spring-boot-cache-redis-ecosystem-patterns`

## Scope

- Applied the workshop 7-Tier review lane to the Redis-backed Spring Cache example.
- Prioritized bluetape4k ecosystem usage and Kotlin style without changing the example architecture.
- Kept the module PR-sized and isolated from the repository-wide coordination plan.

## 7-Tier Findings

| Tier | Result | Evidence |
|---|---|---|
| Correctness | PASS | `findByCode()` and `evictCache()` now reject blank cache keys before Redis cache work. |
| Security | PASS | Blank/invalid keys are rejected with `requireNotBlank()`; no secret, SQL, path, or unsafe reflection findings in the touched scope. |
| Performance | PASS | The 400 ms loader delay remains intentional example behavior; cached lookup and eviction behavior remain covered by integration tests. |
| Stability | PASS | Redis Testcontainers verification ran serially with `--max-workers=1`; Redis write/evict propagation waits stay bounded at 10 ms. |
| Operations | PASS | README commands now use the registered Gradle project path `:spring-boot-cache-redis`. |
| Developer API | PASS | Public example API gained English KDoc and explicit validation contract. |
| User/Docs | PASS | English and Korean READMEs document the bluetape4k Redis launcher, serializer, and validation helpers. |

## bluetape4k Ecosystem Usage

- `RedisServer.Launcher.redis` remains the Redis Testcontainers launcher for local/test Redis.
- `RedisBinarySerializers.LZ4Kryo` remains the documented Redis binary serializer example.
- `requireNotBlank()` from `bluetape4k-core` now protects cache load/evict inputs.
- `io.bluetape4k.assertions.assertFailsWith` now verifies validation behavior in tests.

## Intentional Exceptions

- `Thread.sleep(400)` is retained in production example code because it is the cache-miss simulation that demonstrates Redis cache acceleration.
- `Thread.sleep(10)` is retained in Redis cache tests as bounded Redis write/evict propagation stabilization. A direct Redis key-count assertion was rejected because Spring Redis cache key serialization/prefix behavior is less readable for workshop users than behavior-level cache timing evidence.

## Verification

```bash
./gradlew :spring-boot-cache-redis:compileKotlin :spring-boot-cache-redis:compileTestKotlin :spring-boot-cache-redis:cleanTest :spring-boot-cache-redis:test --no-build-cache --max-workers=1 --warning-mode all --console=plain
```

Result: PASS, 6 tests executed.

Additional local gates:

- `git diff --check`: PASS
- Pattern scan for `TODO`, `FIXME`, `runBlocking`, `!!`, forbidden assertion helpers, direct `GenericContainer`, and deprecated Exposed imports: PASS
- Testcontainers scan: PASS, Redis uses `RedisServer.Launcher.redis`

Remaining PR gate:

- live PR metadata/body verification after PR creation
