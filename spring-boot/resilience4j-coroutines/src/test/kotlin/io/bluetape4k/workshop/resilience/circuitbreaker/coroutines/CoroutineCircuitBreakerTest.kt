package io.bluetape4k.workshop.resilience.circuitbreaker.coroutines

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.resilience.circuitbreaker.AbstractCircuitBreakerTest
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class CoroutineCircuitBreakerTest: AbstractCircuitBreakerTest() {

    companion object: KLogging()

    @Nested
    inner class SuspendMethod {

        @Disabled("@CircuitBreaker 어노테이션은 suspend 함수에 대해서는 적용되지 않는 버그가 있습니다. - CoDecorators를 사용하세요")
        @Test
        fun `Backend A - 연속된 예외에 Circuit breaker 가 Open 상태로 전환됩니다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                procedureSuspendFailure(BACKEND_A)
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
                procedureSuspendSuccess(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend A - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureSuspendTimeout(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B - 연속된 예외에 Circuit breaker 가 Open 상태로 전환됩니다`() {
            // 예외가 6번 발생 (call 4회 * retry 3회) - 12회 예외
            // Backend B의 minimumNumberOfCalls = 10 < 12 회 예외
            repeat(4) {
                procedureSuspendFailure(BACKEND_B)
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
                procedureSuspendSuccess(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureSuspendTimeout(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        private fun procedureSuspendFailure(serviceName: String) {
            webClient.httpGet("/coroutines/$serviceName/suspendFailure", HttpStatus.INTERNAL_SERVER_ERROR)
        }

        private fun procedureSuspendSuccess(serviceName: String) {
            webClient.httpGet("/coroutines/$serviceName/suspendSuccess")
        }

        private fun procedureSuspendTimeout(serviceName: String) {
            webClient.httpGet("/coroutines/$serviceName/suspendTimeout")  // fallback 이 작동하므로, 항상 성공한다
        }
    }

    @Nested
    inner class FlowMethod {

        @Disabled("Retry annotation이 Flow 에 대해서는 적용되지 않는다. Flow.retry(retry) 함수를 사용하세요")
        @Test
        fun `Backend A - 연속된 예외에 Circuit breaker 가 Open 상태로 전환됩니다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                procedureFlowFailure(BACKEND_A)
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
                procedureFlowSuccess(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend A - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureFlowTimeout(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B - 연속된 예외에 Circuit breaker 가 Open 상태로 전환됩니다`() {
            // 예외가 6번 발생 (call 4회 * retry 3회) - 12회 예외
            // Backend B의 minimumNumberOfCalls = 10 < 12 회 예외
            repeat(4) {
                procedureFlowFailure(BACKEND_B)
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
                procedureFlowSuccess(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureFlowTimeout(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        private fun procedureFlowFailure(serviceName: String) {
            webClient.httpGet("/coroutines/$serviceName/flowFailure", HttpStatus.INTERNAL_SERVER_ERROR)
        }

        private fun procedureFlowSuccess(serviceName: String) {
            webClient.httpGet("/coroutines/$serviceName/flowSuccess")
        }

        private fun procedureFlowTimeout(serviceName: String) {
            webClient.httpGet("/coroutines/$serviceName/flowTimeout")  // fallback 이 작동하므로, 항상 성공한다
        }
    }
}
