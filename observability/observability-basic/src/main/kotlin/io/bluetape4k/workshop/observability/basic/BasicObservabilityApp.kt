package io.bluetape4k.workshop.observability.basic

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Basic observability demo: HTTP (WebFlux) → coroutine service → outbound WebClient.
 *
 * Demonstrates manual span instrumentation via `withObservationSuspending` and
 * W3C traceparent propagation through Spring Boot's auto-configured WebClient.Builder.
 */
@SpringBootApplication
class BasicObservabilityApp

fun main(args: Array<String>) {
    runApplication<BasicObservabilityApp>(*args)
}
