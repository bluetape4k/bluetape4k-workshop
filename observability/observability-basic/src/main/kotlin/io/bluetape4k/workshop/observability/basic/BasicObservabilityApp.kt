package io.bluetape4k.workshop.observability.basic

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * basic observability demo 입니다. HTTP(WebFlux) → coroutine service → outbound WebClient 흐름을 보여줍니다.
 *
 * local `observed()` helper(`finally { stop() }` 에 안전한 coroutine wrapper, `ObservationSupport.kt` 참고)를 통한 manual span instrumentation 과 Spring Boot 가 auto-configure 한 WebClient.Builder 를 통한 W3C traceparent propagation 을 시연합니다.
 */
@SpringBootApplication
class BasicObservabilityApp

fun main(args: Array<String>) {
    runApplication<BasicObservabilityApp>(*args)
}
