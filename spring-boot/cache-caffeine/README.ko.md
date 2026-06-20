# Cache Caffeine Demo

[English](README.md) | 한국어

이 모듈은 Spring Cache와 Caffeine을 사용한 로컬 인메모리 캐시 예제입니다. 구조는 의도적으로 작습니다. `CountryRepository`가 500ms 지연 로드를 흉내 내고, Spring Cache가 생성된 `Country` payload를 Caffeine에 저장합니다.

## 아키텍처

![Cache Caffeine Demo architecture](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-readme-architecture-01.png)

`CaffeineConfig`는 Spring Cache를 활성화하고 bluetape4k `caffeine { }` DSL로 Caffeine 인스턴스를 만듭니다. 캐시는 최대 1,000개 엔트리, 5분 access 만료, stats 기록, cache work용 `VirtualThreadExecutor`를 사용합니다.

## 캐시 흐름

![Cache Caffeine Demo lookup sequence](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-readme-cache-sequence-01.png)

`CountryRepository.findByCode(code)`에는 `@Cacheable`이 적용되어 있습니다. 첫 호출은 느린 로드를 기다리고, 두 번째 호출은 local cache에서 바로 반환됩니다. `evictCache(code)`는 엔트리를 제거해 다음 호출이 다시 로드되게 합니다.

## 주요 구성 요소

| 클래스 | 역할 |
|---|---|
| `CaffeineConfig` | `CaffeineCacheManager`와 bluetape4k `caffeine { }` builder를 등록합니다. |
| `CountryRepository` | 국가 조회 메서드에 `@Cacheable`, `@CacheEvict`를 적용합니다. |
| `CountryPrefetcher` | 캐시된 repository를 통해 무작위 국가 코드를 주기적으로 예열합니다. |
| `SchedulingConfig` | virtual-thread scheduled executor로 scheduling을 활성화합니다. |

## 사용한 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `caffeine { }` DSL | `bluetape4k-cache-core` | `CaffeineConfig.caffeineBean()` | Kotlin 친화적인 Caffeine builder |
| `VirtualThreadExecutor` | `bluetape4k-core` | `CaffeineConfig.caffeineBean()` | Caffeine executor 작업을 virtual thread에서 실행 |
| `KLoggingChannel` | `bluetape4k-logging` | Companion objects | Lazy structured logging |
| `randomString()` | `bluetape4k-core` | `Country.kt` | 의미 있는 크기의 cache payload 생성 |

## 캐시 설정 예제

```kotlin
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CaffeineConfig {
    @Bean
    fun cacheManager(caffeine: Caffeine<Any, Any>): CacheManager =
        CaffeineCacheManager("cache:contries", "cache:cities").apply {
            setCaffeine(caffeine)
        }

    @Bean
    fun caffeineBean(): Caffeine<Any, Any> = caffeine {
        initialCapacity(100)
        maximumSize(1000)
        expireAfterAccess(5.minutes.toJavaDuration())
        recordStats()
        executor(VirtualThreadExecutor)
    }
}
```

## 실행

```bash
./gradlew :spring-boot:cache-caffeine:bootRun
./gradlew :spring-boot:cache-caffeine:test
```

## 참고

- [Caffeine GitHub](https://github.com/ben-manes/caffeine)
- [Spring Cache Abstraction](https://docs.spring.io/spring-framework/reference/integration/cache.html)
- [bluetape4k-cache-core](https://github.com/bluetape4k/bluetape4k-projects)
