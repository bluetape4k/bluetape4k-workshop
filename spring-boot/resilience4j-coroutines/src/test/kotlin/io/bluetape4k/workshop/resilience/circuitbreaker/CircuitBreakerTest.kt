package io.bluetape4k.workshop.resilience.circuitbreaker

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldStartWith
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.returnResult

class CircuitBreakerTest: AbstractCircuitBreakerTest() {

    companion object: KLoggingChannel()

    @Nested
    inner class BackendA {

        @Test
        fun `Backend A - 연속된 예외에 Circuit breaker 가 Open 상태로 전환됩니다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                procedureFailure(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.OPEN)
        }

        @Test
        fun `Backend A - 연속적으로 작업이 성공하면 Circuit Breaker는 Closed 상태로 전환됩니다`() {
            // Circuit breaker 는 OPEN -> HALF OPEN -> CLOSED 상태로 전환됩니다 (OPEN에서 바로 CLOSED로 전환되지 않습니다)
            // 1. Circuit breaker를 OPEN 상태로 전환
            transitionToOpenState(BACKEND_A)
            // 2.Circuit breaker를 HALF_OPEN 상태로 전환
            transitionToHalfOpenState(BACKEND_A)
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.HALF_OPEN)

            // permittedNumberOfCallsInHalfOpenState = 3 으로 4회 성공하면 CLOSED 로 전환됩니다.
            repeat(4) {
                procedureSuccess(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend A - 예외 발생 시 Fallback 이 작동합니다`() = runSuspendIO {
            val response = webClient
                .get()
                .uri("/$BACKEND_A/fallback")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult<String>().responseBody
                .awaitSingle()

            response shouldStartWith "Recovered HttpServerErrorException:"
        }

        @Test
        fun `Backend A - Circuit Breaker에 의해 무시되는 예외가 발생하는 경우에는 상태 변환이 없다`() = runSuspendIO {
            // 예외가 발생하지만, Circuit Breaker가 무시하는 예외라면 Circuit Breaker 상태에 영향을 미치지 않는다.
            // Backend A의 minimumNumberOfCalls = 5 < 6 회 예외
            repeat(6) {
                webClient
                    .get()
                    .uri("/$BACKEND_A/ignore")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            }

            // Circuit Breaker 가 BusinessException 예외를 무시했기 때문에 상태 변화는 없다
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }
    }

    @Nested
    inner class BackendB {

        @Test
        fun `Backend B - 연속된 예외에 Circuit breaker 가 Open 상태로 전환됩니다`() {
            // 예외가 6번 발생 (call 4회 * retry 3회) - 12회 예외
            // Backend B의 minimumNumberOfCalls = 10 < 12회 예외
            repeat(4) {
                procedureFailure(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.OPEN)
        }

        @Test
        fun `Backend B - 연속적으로 작업이 성공하면 Circuit Breaker는 Closed 상태로 전환됩니다`() {
            // Circuit breaker 는 OPEN -> HALF OPEN -> CLOSED 상태로 전환됩니다 (OPEN에서 바로 CLOSED로 전환되지 않습니다)
            // 1. Circuit breaker를 OPEN 상태로 전환
            transitionToOpenState(BACKEND_B)
            // 2.Circuit breaker를 HALF_OPEN 상태로 전환
            transitionToHalfOpenState(BACKEND_B)
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.HALF_OPEN)

            // permittedNumberOfCallsInHalfOpenState = 3 으로 4회 성공하면 CLOSED 로 전환됩니다.
            repeat(4) {
                procedureSuccess(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B - 예외 발생 시 Fallback 이 작동합니다`() = runSuspendIO {
            val response = webClient
                .get()
                .uri("/$BACKEND_B/fallback")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult<String>().responseBody
                .awaitSingle()

            response shouldStartWith "Recovered"
        }

        @Test
        fun `Backend B - Circuit Breaker에 의해 무시되는 예외가 발생하는 경우에는 상태 변환이 없다`() = runSuspendIO {
            // 예외가 발생하지만, Circuit Breaker가 무시하는 예외라면 Circuit Breaker 상태에 영향을 미치지 않는다.
            // Backend B의 minimumNumberOfCalls = 10 < 11 회 예외
            repeat(11) {
                webClient
                    .get()
                    .uri("/$BACKEND_A/ignore")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            }

            // Circuit Breaker 가 BusinessException 예외를 무시했기 때문에 상태 변화는 없다
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }
    }

    private fun procedureFailure(backendName: String) {
        webClient
            .get()
            .uri("/$backendName/failure")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    private fun procedureSuccess(backendName: String) {
        webClient
            .get()
            .uri("/$backendName/success")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.OK)
    }
}
