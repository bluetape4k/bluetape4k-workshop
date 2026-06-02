# Micrometer Observation with Spring MVC

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Micrometer Observation with Spring MVC** as a runnable metrics, tracing, and observation workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `observability-micrometer-observation`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Micrometer Observation API를 Spring MVC와 연동하는 예제입니다.
`@Observed` 어노테이션과 `ObservationRegistry`를 통해 메서드 실행에 자동으로 메트릭·트레이싱을 부착합니다.

## Architecture

![Micrometer Observation with Spring MVC Graphviz architecture diagram](../../docs/images/readme-diagrams/observability-micrometer-observation-readme-architecture-01.png)

![micrometer observation Architecture diagram](../../docs/images/readme-diagrams/observability-micrometer-observation-diagram-01.png)

## Key Components

| Class | Role |
|---|---|
| `ObservationAspectConfig` | Registers `ObservedAspect` bean for `@Observed` AOP processing |
| `ObservationLoggingConfig` | Registers an `ObservationHandler` that logs observation events |
| `ObservationFilterConfig` | Filters specific observations (e.g., exclude actuator paths) |
| `GreetingService` | Service with `@Observed` — span created automatically per method call |
| `GreetingController` | REST endpoint (`/greeting`) |
| `ObservationSupport` | `ObservationRegistry` utility extension functions |

## `@Observed` Usage

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

### Fine-grained Span with Key-Values

```kotlin
fun sayHelloWithName(name: String): String {
    return Observation.createNotStarted("$GREETING_SERVICE_NAME.sayHelloWithName", observationRegistry)
        .contextualName("sayHello-with-name")
        .lowCardinalityKeyValue("name", name)       // searchable tag
        .highCardinalityKeyValue("requestId", "1234") // high-cardinality for trace detail
        .observeOrNull { "Hello, $name" }!!
}
```

## Used bluetape4k Features

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| Structured logging (`KLogging`, `KotlinLogging.logger`) | `bluetape4k-logging` | `GreetingService`, `ObservationLoggingConfig` | Kotlin DSL lazy log lambdas — no unnecessary string allocation |
| `debug {}` / `info {}` extension functions | `bluetape4k-logging` | All source files | Eliminates `if (log.isDebugEnabled)` boilerplate |
| JUnit 5 extensions (`@TestInstance`, `shouldNotBeNull`, etc.) | `bluetape4k-junit5` | `ObservationRegistryTest` | Concise Kotlin-style assertion chains |
| Jackson 3.x serialization support | `bluetape4k-jackson3` | REST API responses | Spring Boot 4 + Jackson 3 auto-configuration compatibility |

## Before / After

### Structured Logging (Kotlin DSL)

```kotlin
// Before — standard SLF4J
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
    log.debug { "call sayHelloInternal" }  // lazy lambda — no string construction unless debug is enabled
    return "Hello, World!"
}
```

### Observation Event Logging Handler

```kotlin
// Before — standard Micrometer ObservationHandler, manual logger creation
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

### `observeOrNull` — Null-Safe Observation Wrapper

```kotlin
// Before — standard Micrometer with manual null handling
fun observe(registry: ObservationRegistry, block: () -> String?): String? {
    val obs = Observation.createNotStarted("my-obs", registry).start()
    return try {
        block()
    } catch (e: Exception) {
        obs.error(e)
        throw e
    } finally {
        obs.stop()
    }
}

// After — bluetape4k observeOrNull extension (from ObservationSupport)
fun sayHelloWithName(name: String): String {
    return Observation.createNotStarted("greetingService.sayHelloWithName", observationRegistry)
        .contextualName("sayHello-with-name")
        .lowCardinalityKeyValue("name", name)
        .observeOrNull { "Hello, $name" }!!  // null-safe, exception-aware wrapper
}
```

## Observation Propagation Across Layers

```
HTTP Request
    └── GreetingController         (outer span: HTTP server span)
            └── GreetingService    (@Observed AOP span: greetingService)
                    └── sayHelloWithName  (nested span: greetingService.sayHelloWithName)
```

`@Observed` at class level creates one span per method call. Nesting is handled automatically by `ObservationRegistry` thread-local state.

## Tests

- `ObservationRegistryTest` — direct `ObservationRegistry` API verification
- `GreetingServiceTracingIntegrationTest` — integration tracing validation with `TestObservationRegistry`

## Running

```bash
# Start the application
./gradlew :observability-micrometer-observation:bootRun

# Run tests
./gradlew :observability-micrometer-observation:test
```

## References

- [Micrometer Observation official docs](https://micrometer.io/docs/observation)
- [Spring Boot Actuator + Micrometer](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [`micrometer-tracing-coroutines`](../micrometer-tracing-coroutines) — Coroutine tracing with `withObservationSuspending`
