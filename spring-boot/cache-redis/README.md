# Redis Cache Demo

[한국어](README.ko.md) | English

This module shows Spring Cache backed by Redis and Lettuce. It mirrors the Caffeine cache sample, but stores `Country` payloads in Redis with bluetape4k binary serialization and runs Lettuce work on a virtual-thread executor.

## Architecture

![Redis Cache Demo architecture](../../docs/images/readme-diagrams/spring-boot-cache-redis-readme-architecture-01.png)

`RedisCacheApplication` starts `RedisServer.Launcher.redis`, so tests and local runs can use the same Testcontainers Redis endpoint. `LettuceRedisCacheConfiguration` wires `LettuceConnectionFactory`, `RedisCacheManager`, and `RedisTemplate` with `RedisBinarySerializers.LZ4Kryo`.

## Cache Flow

![Redis Cache Demo lookup sequence](../../docs/images/readme-diagrams/spring-boot-cache-redis-readme-cache-sequence-01.png)

`CountryRepository.findByCode(code)` uses `@Cacheable`. The first call executes the slow method and writes a Redis entry; the next call is served from Redis. `evictCache(code)` removes the Redis key.

## Main Components

| Class | Role |
|---|---|
| `LettuceRedisCacheConfiguration` | Configures `RedisCacheManager`, `RedisTemplate`, binary serialization, and Lettuce connection factory |
| `AsyncConfig` | Owns the shared `VirtualThreads` executor, adapts it for Spring, and restores MDC after every task |
| `CountryRepository` | Applies bluetape4k validation plus `@Cacheable` and `@CacheEvict` to Redis-backed country lookup |

## bluetape4k Features Used

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| `RedisServer.Launcher.redis` | `bluetape4k-testcontainers` | `RedisCacheApplication` | Starts a shared Redis Testcontainers instance for tests and local runs |
| `RedisBinarySerializers.LZ4Kryo` | `bluetape4k-spring-boot4-redis` | `LettuceRedisCacheConfiguration.redisTemplate()` | Stores compact binary values in Redis |
| `requireNotBlank()` | `bluetape4k-core` | `CountryRepository.findByCode()` and `evictCache()` | Rejects invalid cache keys before loading or eviction |
| `VirtualThreads.executorService()` | `bluetape4k-virtualthread-api` + `bluetape4k-virtualthread-jdk25` | `AsyncConfig` | Selects the supported provider or platform fallback while Spring owns shutdown |
| `CountryPrefetcher` | Warms random country codes when the `app` profile is active |

## Executor Ownership

```kotlin
@Bean(name = ["cacheRedisVirtualThreadExecutor"], destroyMethod = "shutdown")
fun cacheRedisVirtualThreadExecutor(): ExecutorService = VirtualThreads.executorService()

@Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
fun asyncTaskExecutor(executorService: ExecutorService): AsyncTaskExecutor =
    TaskExecutorAdapter(executorService).apply {
        setTaskDecorator(LoggingTaskDecorator())
    }
```

Spring owns one delegate used by both `@Async` and Lettuce. Context shutdown rejects new work but does not
interrupt already submitted tasks or wait indefinitely for them. The provider-defined thread name is intentional;
use the `cacheRedisVirtualThreadExecutor` bean name and `VirtualThreads.runtimeName()` for observation. The MDC
decorator restores the worker's previous context in `finally`, including error and empty-caller paths.
Both virtual-thread artifacts are versionless module declarations resolved by `bluetape4k-dependencies:2.0.0`.

## Redis Configuration Example

```kotlin
@Bean
fun lettuceConnectionFactory(applicationTaskExecutor: AsyncTaskExecutor): LettuceConnectionFactory =
    LettuceConnectionFactory(RedisStandaloneConfiguration(redisHost, redisPort)).apply {
        setExecutor(applicationTaskExecutor)
    }

@Bean
fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<Any, Any> =
    RedisTemplate<Any, Any>().apply {
        setConnectionFactory(connectionFactory)
        setDefaultSerializer(RedisBinarySerializers.LZ4Kryo)
        keySerializer = StringRedisSerializer.UTF_8
        valueSerializer = RedisBinarySerializers.LZ4Kryo
    }
```

## Run

```bash
./gradlew :spring-boot-cache-redis:test
./gradlew :spring-boot-cache-redis:bootRun
```

## References

- [Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/)
- [Lettuce](https://lettuce.io/)
- [bluetape4k-spring-boot4-redis](https://github.com/bluetape4k/bluetape4k-projects)
- [bluetape4k virtual-thread API](https://github.com/bluetape4k/bluetape4k-projects/tree/develop/virtualthread)
