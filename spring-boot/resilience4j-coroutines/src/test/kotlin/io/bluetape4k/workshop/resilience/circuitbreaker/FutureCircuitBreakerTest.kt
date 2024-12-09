package io.bluetape4k.workshop.resilience.circuitbreaker

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.tests.httpGet
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class FutureCircuitBreakerTest: AbstractCircuitBreakerTest() {

    companion object: KLogging()

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

            // permittedNumberOfCallsInHalfOpenState = 3 으로 3회 성공하면 CLOSED 로 전환됩니다.
            repeat(3) {
                procedureSuccess(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend A - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureTimeout(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }
    }

    @Nested
    inner class BackendB {
        @Test
        fun `Backend B - 연속된 예외에 Circuit breaker 가 Open 상태로 전환됩니다`() {
            // 예외가 6번 발생 (call 4회 * retry 3회) - 12회 예외
            // Backend B의 minimumNumberOfCalls = 10 < 12 회 예외
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

            // permittedNumberOfCallsInHalfOpenState = 3 으로 3회 성공하면 CLOSED 로 전환됩니다.
            repeat(3) {
                procedureSuccess(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // Timeout 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외 (단 fallback 이 작동하므로, 항상 성공한다)
            repeat(2) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureTimeout(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }
    }

    private fun procedureFailure(serviceName: String) {
        webClient.httpGet("/$serviceName/futureFailure", HttpStatus.INTERNAL_SERVER_ERROR)
    }

    private fun procedureSuccess(serviceName: String) {
        webClient.httpGet("/$serviceName/futureSuccess")
    }

    private fun procedureTimeout(serviceName: String) {
        webClient.httpGet("/$serviceName/futureTimeout")   // fallback 이 작동하므로, 항상 성공한다
    }
}
