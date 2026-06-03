# Redis Cache Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Redis Cache Demo**를 실행 가능한 Spring Boot 애플리케이션 기능 워크숍 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Redis Cache Demo Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/spring-boot-cache-redis-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지를 기준으로 삼습니다.

## 시퀀스 다이어그램

Spring Data Redis + Lettuce를 사용하고 bluetape4k의 `RedisBinarySerializers`(LZ4 + Kryo)와 Virtual Thread async executor로 뒷받침되는 분산 캐시 예제입니다.
통합 테스트에서는 Testcontainers가 Redis 컨테이너를 자동으로 시작합니다.

## 개요

이 모듈은 JSON 직렬화를 bluetape4k 바이너리 직렬화기(`RedisBinarySerializers.LZ4Kryo`)로 대체해 Redis 저장 공간을 50-70% 줄이고,
고정 스레드 풀 대신 Virtual Threads로 Lettuce I/O를 처리하는 방법을 보여 줍니다.

---

## 상세 설명

이 예제는 Spring Data Redis와 Lettuce로 Redis 위에 Spring Cache 추상화를 구현합니다.
bluetape4k `RedisBinarySerializers`(LZ4 + Kryo)와 Virtual Thread 기반 async executor를 사용합니다.
Testcontainers는 통합 테스트를 위해 Redis 컨테이너를 자동으로 시작합니다.

## 주요 구성 요소

| 클래스 | 역할 |
|---|---|
| `LettuceRedisCacheConfiguration` | `RedisCacheManager` 빈과 `RedisBinarySerializers` 직렬화를 구성합니다. |
| `AsyncConfig` | Virtual Thread 기반 `AsyncTaskExecutor`와 MDC 전파를 구성합니다. |
| `CountryRepository` | `@Cacheable`과 `@CacheEvict` 애노테이션을 적용합니다. |
| `CountryPrefetcher` | 스케줄러로 캐시를 주기적으로 예열합니다. |

## 사용한 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `RedisBinarySerializers.LZ4Kryo` | `bluetape4k-spring-boot4-redis` | `LettuceRedisCacheConfiguration.redisTemplate()` | LZ4 압축 + Kryo 직렬화로 Redis 저장 공간 절감 |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | 코루틴 컨텍스트를 포함한 구조적 로깅 |
| Virtual Thread Executor | `bluetape4k-coroutines` | `AsyncConfig` | Lettuce I/O를 Virtual Threads에서 처리 |
| `randomString()` | `bluetape4k-core` | `Country.kt` | 캐시 페이로드용 테스트 데이터 생성 |

## bluetape4k 적용 전 / 후

### `RedisBinarySerializers`와 기존 JSON 직렬화 비교

```kotlin
// Before — GenericJackson2JsonRedisSerializer (text-based, larger payloads)
@Bean
fun redisCacheManager(connectionFactory: RedisConnectionFactory): RedisCacheManager =
    RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(
            RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(
                    RedisSerializationContext.SerializationPair
                        .fromSerializer(GenericJackson2JsonRedisSerializer())
                )
        )
        .build()

// After — RedisBinarySerializers.LZ4Kryo (binary compression, 50-70% less storage)
@Bean
fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<Any, Any> {
    return RedisTemplate<Any, Any>().apply {
        setConnectionFactory(connectionFactory)
        setDefaultSerializer(RedisBinarySerializers.LZ4Kryo)
        keySerializer = StringRedisSerializer.UTF_8
        valueSerializer = RedisBinarySerializers.LZ4Kryo
    }
}
```

### Virtual Thread Executor와 일반 Thread Pool 비교

```kotlin
// Before — ThreadPoolTaskExecutor (OS thread based)
@Bean
fun asyncTaskExecutor(): AsyncTaskExecutor =
    ThreadPoolTaskExecutor().apply {
        corePoolSize = 10
        maxPoolSize = 50
        setThreadNamePrefix("async-")
    }

// After — Virtual Thread per task (lightweight threads managed by the JVM)
@Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
@Primary
fun asyncTaskExecutor(): AsyncTaskExecutor {
    val factory = Thread.ofVirtual().name("async-vt-exec-", 0).factory()
    return TaskExecutorAdapter(Executors.newThreadPerTaskExecutor(factory)).apply {
        setTaskDecorator(LoggingTaskDecorator())  // MDC context propagation
    }
}
```

## Redis 캐시 설정 예제

```kotlin
@Bean
fun lettuceConnectionFactory(applicationTaskExecutor: AsyncTaskExecutor): LettuceConnectionFactory {
    val configuration = RedisStandaloneConfiguration(redisHost, redisPort)
    // Runs Lettuce work on Virtual Threads
    return LettuceConnectionFactory(configuration).apply {
        setExecutor(applicationTaskExecutor)
    }
}

@Bean
fun redisCacheManager(connectionFactory: RedisConnectionFactory): CacheManager =
    RedisCacheManager.builder(connectionFactory)
        .transactionAware()
        .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(1)))
        .build()
```

## 실행

```bash
# Testcontainers starts the Redis container automatically
./gradlew :cache-redis:test

./gradlew :cache-redis:bootRun
```

## 참고

- [Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/)
- [Lettuce](https://lettuce.io/)
- [bluetape4k-spring-boot4-redis](https://github.com/bluetape4k/bluetape4k-projects)
