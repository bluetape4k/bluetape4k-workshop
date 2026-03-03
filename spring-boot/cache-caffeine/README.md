# Cache Caffeine Demo

[Caffeine](https://github.com/ben-manes/caffeine)을 Spring Cache 추상화와 연동하는 예제입니다.

## 주요 구성

| 클래스 | 역할 |
|---|---|
| `CaffeineConfig` | `CacheManager` 빈 구성 (TTL, 최대 크기 설정) |
| `CountryRepository` | `@Cacheable`, `@CacheEvict` 어노테이션 적용 |
| `CountryPrefetcher` | 스케줄러로 캐시를 주기적으로 워밍업(pre-fetch) |
| `SchedulingConfig` | `@EnableScheduling` 설정 |

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
