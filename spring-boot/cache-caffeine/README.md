# Cache Caffeine Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Cache Caffeine Demo** as a runnable Spring Boot application feature workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Cache Caffeine Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springboot` as the source of truth when comparing this README with the code.

![Cache Caffeine Demo architecture diagram](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-architecture-01.png)

## Flow Diagram

1. Prepare the local runtime required by `spring-boot-cache-caffeine`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

![Cache Caffeine Demo flow diagram](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-diagram-01.png)

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Local in-memory cache example using [Caffeine](https://github.com/ben-manes/caffeine) with Spring Cache abstraction.
Demonstrates the bluetape4k `caffeine { }` DSL and `VirtualThreadExecutor` for lazy-loading cache entries.

## Overview

This module shows how to wire Caffeine as a Spring `CacheManager` using the bluetape4k Kotlin DSL.
Key features: type-safe builder with `kotlin.time.Duration`, Virtual Thread executor for lazy loading,
and structured logging with `KLoggingChannel`.

---

## 상세-설명

[Caffeine](https://github.com/ben-manes/caffeine)을 Spring Cache 추상화와 연동하는 예제입니다.
bluetape4k의 `caffeine { }` DSL과 `VirtualThreadExecutor`로 로컬 인메모리 캐시를 구성합니다.

## 아키텍처

![cache caffeine Architecture diagram](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-architecture-01.png)

## 주요 구성

| 클래스 | 역할 |
|---|---|
| `CaffeineConfig` | `CacheManager` 빈 구성 — bluetape4k `caffeine { }` DSL + `VirtualThreadExecutor` |
| `CountryRepository` | `@Cacheable`, `@CacheEvict` 어노테이션 적용 |
| `CountryPrefetcher` | 스케줄러로 캐시를 주기적으로 워밍업(pre-fetch) |
| `SchedulingConfig` | `@EnableScheduling` 설정 |

## 사용된 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `caffeine { }` DSL | `bluetape4k-cache-core` | `CaffeineConfig.caffeineBean()` | 타입 안전 빌더, Kotlin Duration 직접 사용 |
| `VirtualThreadExecutor` | `bluetape4k-coroutines` | `CaffeineConfig.caffeineBean()` | Lazy loading을 Virtual Thread로 실행 |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | 코루틴 컨텍스트 포함 구조적 로깅 |
| `randomString()` | `bluetape4k-core` | `Country.kt` | 캐시 페이로드 테스트 데이터 생성 |

## bluetape4k Before / After

### `caffeine { }` DSL vs 기존 빌더

```kotlin
// Before — Java Caffeine.newBuilder() 방식
@Bean
fun cacheManager(): CacheManager = CaffeineCacheManager().apply {
    setCaffeine(
        Caffeine.newBuilder()
            .initialCapacity(100)
            .maximumSize(1_000)
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .recordStats()
    )
}

// After — bluetape4k caffeine { } DSL (Kotlin Duration, 가독성 향상)
@Bean
fun caffeineBean(): Caffeine<Any, Any> = caffeine {
    initialCapacity(100)
    maximumSize(1000)
    expireAfterAccess(5.minutes.toJavaDuration())  // kotlin.time.Duration 직접 사용
    recordStats()
    executor(VirtualThreadExecutor)                // Virtual Thread로 lazy loading
}
```

### `KLoggingChannel` vs 기존 로거

```kotlin
// Before — LoggerFactory 직접 사용
private val logger = LoggerFactory.getLogger(CaffeineConfig::class.java)

// After — KLoggingChannel (코루틴 MDC 컨텍스트 전파 포함)
companion object: KLoggingChannel()

log.debug { "Loading country with code[$code]..." }  // lazy lambda
```

## 캐시 설정 예시

```kotlin
@Configuration
@EnableCaching
class CaffeineConfig {

    companion object: KLoggingChannel()

    @Bean
    fun cacheManager(caffeine: Caffeine<Any, Any>): CacheManager {
        return CaffeineCacheManager("cache:countries", "cache:cities").apply {
            setCaffeine(caffeine)
        }
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
./gradlew :cache-caffeine:bootRun
```

## 참고

- [Caffeine GitHub](https://github.com/ben-manes/caffeine)
- [Spring Cache Abstraction](https://docs.spring.io/spring-framework/reference/integration/cache.html)
- [bluetape4k-cache-core](https://github.com/bluetape4k/bluetape4k-projects)
