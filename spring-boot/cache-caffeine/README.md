# Cache Caffeine Demo

[Caffeine](https://github.com/ben-manes/caffeine)을 Spring Cache 추상화와 연동하는 예제입니다.

## 주요 구성

| 클래스 | 역할 |
|---|---|
| `CaffeineConfig` | `CacheManager` 빈 구성 (TTL, 최대 크기 설정) |
| `CountryRepository` | `@Cacheable`, `@CacheEvict` 어노테이션 적용 |
| `CountryPrefetcher` | 스케줄러로 캐시를 주기적으로 워밍업(pre-fetch) |
| `SchedulingConfig` | `@EnableScheduling` 설정 |

## 캐시 HIT/MISS 흐름

```mermaid
flowchart LR
    클라이언트 -->|요청| CountryRepository

    subgraph 캐시 레이어
        CountryRepository -->|@Cacheable 조회| CaffeineCache{캐시\nHIT?}
        CaffeineCache -->|HIT| 캐시결과[캐시된 데이터 반환]
        CaffeineCache -->|MISS| DB[(H2 데이터베이스)]
        DB -->|결과 저장| CaffeineCache
        DB -->|데이터 반환| CountryRepository
    end

    subgraph 캐시 관리
        CountryPrefetcher -->|@Scheduled 주기 실행| CountryRepository
        CountryRepository -->|@CacheEvict| CaffeineCache
        CaffeineConfig -->|TTL 10분 / 최대 1000개| CaffeineCache
    end

    캐시결과 -->|응답| 클라이언트
    CountryRepository -->|응답| 클라이언트
```

## 캐시 설정 예시

```kotlin
@Bean
fun cacheManager(): CacheManager = CaffeineCacheManager().apply {
    setCaffeine(
        Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1_000)
    )
}
```

## 참고

- [Caffeine GitHub](https://github.com/ben-manes/caffeine)
- [Spring Cache Abstraction](https://docs.spring.io/spring-framework/reference/integration/cache.html)
