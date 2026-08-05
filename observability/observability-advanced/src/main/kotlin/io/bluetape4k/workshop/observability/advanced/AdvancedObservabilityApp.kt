package io.bluetape4k.workshop.observability.advanced

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * advanced observability demo 입니다. HTTP(WebFlux) → coroutine service → H2 DB(Exposed JDBC) + Redis cache 흐름을 보여줍니다.
 *
 * released `withObservationContextSuspending` helper 를 통한 multi-layer span instrumentation, soft-fail 을 적용한 Redis cache-aside pattern, coroutine dispatcher boundary propagation 을 시연합니다.
 */
@SpringBootApplication
class AdvancedObservabilityApp

fun main(args: Array<String>) {
    runApplication<AdvancedObservabilityApp>(*args)
}
