package io.bluetape4k.workshop.problem.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.problem.AbstractProblemTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient

class Resilience4jControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractProblemTest() {

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
    fun `when circuit breaker is opened returns CallNotPermittedException`() {
        client.httpGet("/resilience4j/circuit-breaker-open", HttpStatus.SERVICE_UNAVAILABLE)
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
    fun `when retry exceeed max attempts`() = runTest {
        client.httpGet("/resilience4j/retry", HttpStatus.INTERNAL_SERVER_ERROR)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Retry 'default' has exhausted all attempts (3)")
            .consumeWith { result ->
                val body = result.responseBody!!
                log.debug { body.toUtf8String() }
            }
    }
}
