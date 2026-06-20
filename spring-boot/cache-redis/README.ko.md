# Redis Cache Demo

[English](README.md) | 한국어

이 모듈은 Redis와 Lettuce 기반 Spring Cache 예제입니다. Caffeine cache sample과 같은 `Country` 조회 구조를 사용하지만, payload를 Redis에 저장하고 bluetape4k binary serialization과 virtual-thread executor를 적용합니다.

## 아키텍처

![Redis Cache Demo architecture](../../docs/images/readme-diagrams/spring-boot-cache-redis-readme-architecture-01.png)

`RedisCacheApplication`은 `RedisServer.Launcher.redis`를 시작하므로 테스트와 로컬 실행이 같은 Testcontainers Redis endpoint를 사용할 수 있습니다. `LettuceRedisCacheConfiguration`은 `LettuceConnectionFactory`, `RedisCacheManager`, `RedisTemplate`을 `RedisBinarySerializers.LZ4Kryo`와 함께 구성합니다.

## 캐시 흐름

![Redis Cache Demo lookup sequence](../../docs/images/readme-diagrams/spring-boot-cache-redis-readme-cache-sequence-01.png)

`CountryRepository.findByCode(code)`에는 `@Cacheable`이 적용되어 있습니다. 첫 호출은 느린 메서드를 실행하고 Redis entry를 저장하며, 다음 호출은 Redis에서 반환됩니다. `evictCache(code)`는 Redis key를 제거합니다.

## 주요 구성 요소

| 클래스 | 역할 |
|---|---|
| `LettuceRedisCacheConfiguration` | `RedisCacheManager`, `RedisTemplate`, binary serialization, Lettuce connection factory를 구성합니다. |
| `AsyncConfig` | virtual thread와 MDC propagation을 사용하는 primary `AsyncTaskExecutor`를 제공합니다. |
| `CountryRepository` | Redis-backed country lookup에 `@Cacheable`, `@CacheEvict`를 적용합니다. |
| `CountryPrefetcher` | `app` profile에서 무작위 국가 코드를 예열합니다. |

## Redis 설정 예제

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

## 실행

```bash
./gradlew :spring-boot:cache-redis:test
./gradlew :spring-boot:cache-redis:bootRun
```

## 참고

- [Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/)
- [Lettuce](https://lettuce.io/)
- [bluetape4k-spring-boot4-redis](https://github.com/bluetape4k/bluetape4k-projects)
