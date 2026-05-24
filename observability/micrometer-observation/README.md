# Micrometer Observation with Spring MVC

Micrometer Observation API를 Spring MVC와 연동하는 예제입니다.
`@Observed` 어노테이션과 `ObservationRegistry`를 통해 메서드 실행에 자동으로 메트릭·트레이싱을 부착합니다.

## 아키텍처 다이어그램

![micrometer observation Architecture diagram](../../docs/images/readme-diagrams/observability-micrometer-observation-diagram-01.png)

## 주요 구성

| 클래스 | 역할 |
|---|---|
| `ObservationAspectConfig` | `@Observed` AOP 처리를 위한 `ObservedAspect` 빈 등록 |
| `ObservationLoggingConfig` | Observation 이벤트를 로그로 출력하는 Handler 설정 |
| `ObservationFilterConfig` | 특정 Observation 필터링 설정 |
| `GreetingService` | `@Observed`가 적용된 서비스 — 자동으로 span 생성 |
| `GreetingController` | REST 엔드포인트 (`/greeting`) |
| `ObservationSupport` | `ObservationRegistry` 유틸리티 |

## `@Observed` 사용 예시

```kotlin
@Service
@Observed(name = "greeting.service")
class GreetingService(private val registry: ObservationRegistry) {

    fun greet(name: String): String {
        return Observation.createNotStarted("greet", registry)
            .observe { "Hello, $name!" }
    }
}
```

## bluetape4k 활용 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| 구조화된 로깅 (`KLogging`, `KotlinLogging.logger`) | `bluetape4k-logging` | `GreetingService`, `ObservationLoggingConfig` | Kotlin DSL 로그 람다로 지연 평가, 불필요한 문자열 생성 방지 |
| `debug {}` / `info {}` 확장 함수 | `bluetape4k-logging` | 모든 소스 파일 | `if (log.isDebugEnabled)` 보일러플레이트 제거 |
| JUnit 5 확장 (`@TestInstance`, `shouldNotBeNull` 등) | `bluetape4k-junit5` | `ObservationRegistryTest` | 간결한 assertion 체인 |
| Jackson 3.x 직렬화 지원 | `bluetape4k-jackson3` | REST API 응답 | Spring Boot 4 + Jackson 3 호환 자동 설정 |

## bluetape4k Before / After

### 구조화된 로깅 (Kotlin DSL)

```kotlin
// Before — 표준 SLF4J
import org.slf4j.LoggerFactory
private val log = LoggerFactory.getLogger(GreetingService::class.java)

fun sayHello(): String {
    if (log.isDebugEnabled) {
        log.debug("call sayHelloInternal")
    }
    return "Hello, World!"
}

// After — bluetape4k-logging
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug

companion object: KLogging()

fun sayHello(): String {
    log.debug { "call sayHelloInternal" }  // 람다로 지연 평가, 불필요한 문자열 생성 없음
    return "Hello, World!"
}
```

### Observation 이벤트 로깅 핸들러

```kotlin
// Before — 표준 Micrometer ObservationHandler 직접 구현
@Configuration
class ObservationLoggingConfig {
    private val log = LoggerFactory.getLogger("ObservationLogger")

    @Bean
    fun observationLogger(): ObservationHandler<Observation.Context> {
        return ObservationHandler { event ->
            if (log.isDebugEnabled) log.debug("Observation event: $event")
        }
    }
}

// After — bluetape4k-logging + ObservationTextPublisher
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.debug

@Configuration(proxyBeanMethods = false)
class ObservationLoggingConfig {
    private val logger = KotlinLogging.logger("io.bluetape4k.workshop.observation.ObservationLogger")

    @Bean
    fun observationLogger(): ObservationHandler<Observation.Context> {
        return ObservationTextPublisher { logger.debug { it } }
    }
}
```

## 테스트

- `ObservationRegistryTest` — `ObservationRegistry` 직접 테스트
- `GreetingServiceTracingIntegrationTest` — 통합 트레이싱 검증

## 참고

- [Micrometer Observation 공식 문서](https://micrometer.io/docs/observation)
- [Spring Boot Actuator + Micrometer](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [`micrometer-tracing-coroutines`](../micrometer-tracing-coroutines) — Coroutine 환경 트레이싱 예제
