# Cache Caffeine Demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Cache Caffeine Demo**를 실행 가능한 Spring Boot 애플리케이션 기능 워크숍 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Cache Caffeine Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지를 기준으로 삼습니다.

![Cache Caffeine Demo architecture diagram](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-architecture-01.png)

## 흐름 다이어그램

1. `spring-boot-cache-caffeine`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

![Cache Caffeine Demo flow diagram](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-diagram-01.png)

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여 줍니다. 없으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

[Caffeine](https://github.com/ben-manes/caffeine)을 Spring Cache 추상화와 함께 사용하는 로컬 인메모리 캐시 예제입니다.
bluetape4k `caffeine { }` DSL과 지연 로딩 캐시 엔트리를 위한 `VirtualThreadExecutor`를 보여 줍니다.

## 개요

이 모듈은 bluetape4k Kotlin DSL을 사용해 Caffeine을 Spring `CacheManager`로 연결하는 방법을 보여 줍니다.
핵심 기능은 `kotlin.time.Duration`을 지원하는 타입 안전 빌더, 지연 로딩을 위한 Virtual Thread executor,
그리고 `KLoggingChannel` 기반 구조적 로깅입니다.

---

## 상세 설명

이 예제는 [Caffeine](https://github.com/ben-manes/caffeine)을 Spring Cache 추상화와 통합합니다.
bluetape4k `caffeine { }` DSL과 `VirtualThreadExecutor`로 로컬 인메모리 캐시를 구성합니다.

## 아키텍처

![cache caffeine Architecture diagram](../../docs/images/readme-diagrams/spring-boot-cache-caffeine-architecture-01.png)

## 주요 구성 요소

| 클래스 | 역할 |
|---|---|
| `CaffeineConfig` | bluetape4k `caffeine { }` DSL + `VirtualThreadExecutor`로 `CacheManager` 빈을 구성합니다. |
| `CountryRepository` | `@Cacheable`과 `@CacheEvict` 애노테이션을 적용합니다. |
| `CountryPrefetcher` | 스케줄러로 캐시를 주기적으로 예열합니다. |
| `SchedulingConfig` | `@EnableScheduling`을 구성합니다. |

## 사용한 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `caffeine { }` DSL | `bluetape4k-cache-core` | `CaffeineConfig.caffeineBean()` | Kotlin Duration을 직접 지원하는 타입 안전 빌더 |
| `VirtualThreadExecutor` | `bluetape4k-coroutines` | `CaffeineConfig.caffeineBean()` | 지연 로딩을 Virtual Threads에서 실행 |
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | 코루틴 컨텍스트를 포함한 구조적 로깅 |
| `randomString()` | `bluetape4k-core` | `Country.kt` | 캐시 페이로드용 테스트 데이터 생성 |

## bluetape4k 적용 전 / 후

### `caffeine { }` DSL과 기존 빌더 비교

```kotlin
// Before — Java Caffeine.newBuilder() style
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

// After — bluetape4k caffeine { } DSL (Kotlin Duration, improved readability)
@Bean
fun caffeineBean(): Caffeine<Any, Any> = caffeine {
    initialCapacity(100)
    maximumSize(1000)
    expireAfterAccess(5.minutes.toJavaDuration())  // Uses kotlin.time.Duration directly
    recordStats()
    executor(VirtualThreadExecutor)                // Lazy loading on Virtual Threads
}
```

### `KLoggingChannel`과 기존 Logger 비교

```kotlin
// Before — Direct LoggerFactory usage
private val logger = LoggerFactory.getLogger(CaffeineConfig::class.java)

// After — KLoggingChannel (includes coroutine MDC context propagation)
companion object: KLoggingChannel()

log.debug { "Loading country with code[$code]..." }  // lazy lambda
```

## 캐시 설정 예제

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
