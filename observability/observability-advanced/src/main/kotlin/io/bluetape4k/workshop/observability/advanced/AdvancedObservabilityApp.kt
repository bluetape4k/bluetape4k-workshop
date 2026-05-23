package io.bluetape4k.workshop.observability.advanced

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Advanced observability demo: HTTP (WebFlux) → coroutine service → H2 DB (Exposed JDBC) + Redis cache.
 *
 * Demonstrates multi-layer span instrumentation via `withObservationSuspending`,
 * Redis cache-aside pattern with soft-fail, and coroutine dispatcher boundary propagation.
 */
@SpringBootApplication
class AdvancedObservabilityApp

fun main(args: Array<String>) {
    runApplication<AdvancedObservabilityApp>(*args)
}
