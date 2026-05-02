# Redis Cache Demo

Spring Data Redis와 Lettuce를 이용해 Spring Cache 추상화를 Redis로 구현하는 예제입니다.
Testcontainers로 Redis 컨테이너를 자동으로 구동하여 통합 테스트를 수행합니다.

## 주요 구성

| 클래스 | 역할 |
|---|---|
| `LettuceRedisCacheConfiguration` | `RedisCacheManager` 빈 구성 (직렬화, TTL 설정) |
| `CountryRepository` | `@Cacheable`, `@CacheEvict` 어노테이션 적용 |
| `CountryPrefetcher` | 스케줄러로 캐시를 주기적으로 워밍업(pre-fetch) |
| `AsyncConfig` | `@Async` 작업을 위한 Executor 설정 |

## Redis 캐시 HIT/MISS 흐름

```mermaid
flowchart LR
    클라이언트 -->|요청| CountryRepository

    subgraph 캐시 레이어
        CountryRepository -->|@Cacheable 조회| RedisCache{Redis\n캐시 HIT?}
        RedisCache -->|HIT| 캐시결과[직렬화된 데이터 반환]
        RedisCache -->|MISS| DB[(H2 데이터베이스)]
        DB -->|결과 JSON 직렬화 저장| RedisCache
        DB -->|데이터 반환| CountryRepository
    end

    subgraph 캐시 관리
        CountryPrefetcher -->|@Scheduled 워밍업| CountryRepository
        CountryRepository -->|@CacheEvict| RedisCache
        LettuceRedisCacheConfiguration -->|TTL 10분\nJSON 직렬화| RedisCache
    end

    subgraph 인프라
        RedisCache <-->|Lettuce 클라이언트| Redis서버[(Redis)]
    end

    캐시결과 -->|응답| 클라이언트
    CountryRepository -->|응답| 클라이언트
```

## 캐시 설정 예시

```kotlin
@Bean
fun redisCacheManager(connectionFactory: RedisConnectionFactory): RedisCacheManager =
    RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(
            RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(GenericJackson2JsonRedisSerializer())
                )
        )
        .build()
```

## 참고

- [Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/)
- [Lettuce](https://lettuce.io/)
