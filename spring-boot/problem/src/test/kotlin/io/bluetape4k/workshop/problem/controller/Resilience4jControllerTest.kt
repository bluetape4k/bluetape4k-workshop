package io.bluetape4k.workshop.problem.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.problem.AbstractProblemTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class Resilience4jControllerTest: AbstractProblemTest() {

    companion object: KLogging()

    /**
     * When circuit breaker is opened returns call not permitted exception
     *
     * ```json
     * {
     *     "title": "Service Unavailable",
     *     "status": 503,
     *     "detail": "CircuitBreaker 'default' is OPEN and does not permit further calls"
     * }
     * ```
     *
     */
    @Test
    fun `when circuit breaker is opened returns CallNotPermittedException`() = runSuspendIO {
        client
            .get()
            .uri("/resilience4j/circuit-breaker-open")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("CircuitBreaker 'default' is OPEN and does not permit further calls")
            .consumeWith { result ->
                val body = result.responseBody!!
                log.debug { body.toUtf8String() }
            }
    }

    /**
     * When occur [io.github.resilience4j.retry.MaxRetriesExceededException] by Resilience4j Retry
     * -> [Resilience4jTrait]에서 [io.github.resilience4j.retry.MaxRetriesExceededException] 에외에 대해 Problem 으로 정의했다
     *
     * ```json
     * {
     *     "title": "Internal Server Error",
     *     "status": 500,
     *     "detail": "Retry 'default' has exhausted all attempts (3)"
     * }
     * ```
     */
    @Test
    fun `when retry exceeed max attempts`() = runSuspendIO {
        client
            .get()
            .uri("/resilience4j/retry")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Retry 'default' has exhausted all attempts (3)")
            .consumeWith { result ->
                val body = result.responseBody!!
                log.debug { body.toUtf8String() }
            }
    }
}
