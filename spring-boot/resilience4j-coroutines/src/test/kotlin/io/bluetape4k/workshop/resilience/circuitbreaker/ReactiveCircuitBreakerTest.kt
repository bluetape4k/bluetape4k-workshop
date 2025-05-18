package io.bluetape4k.workshop.resilience.circuitbreaker

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.spring.tests.httpGet
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ReactiveCircuitBreakerTest: AbstractCircuitBreakerTest() {

    companion object: KLoggingChannel()

    @Nested
    inner class MonoMethodTest {

        @Test
        fun `Backend A Mono - 연속된 예외에 CircuitBreaker 가 OPEN 됩니다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                procedureMonoFailure(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.OPEN)
        }

        @Test
        fun `Backend A Mono - 연속적으로 작업이 성공하면 Circuit breaker 는 Closed 상태로 전이됩니다`() {
            // Circuit breaker 는 OPEN -> HALF OPEN -> CLOSED 상태로 전환됩니다 (OPEN에서 바로 CLOSED로 전환되지 않습니다)
            // 1. Circuit breaker를 OPEN 상태로 전환
            transitionToOpenState(BACKEND_A)
            // 2.Circuit breaker를 HALF_OPEN 상태로 전환
            transitionToHalfOpenState(BACKEND_A)
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.HALF_OPEN)

            // permittedNumberOfCallsInHalfOpenState = 3 으로 4회 성공하면 CLOSED 로 전환됩니다.
            repeat(4) {
                procedureMonoSuccess(BACKEND_A)
            }

            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend A Mono - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureMonoTimeout(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B Mono - 연속된 예외에 CircuitBreaker 가 OPEN 됩니다`() {
            // 예외가 6번 발생 (call 4회 * retry 3회) - 12회 예외
            // Backend B의 minimumNumberOfCalls = 10 < 12 회 예외
            repeat(4) {
                procedureMonoFailure(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.OPEN)
        }

        @Test
        fun `Backend B Mono - 연속적으로 작업이 성공하면 Circuit breaker 는 Closed 상태로 전이됩니다`() {
            // Circuit breaker 는 OPEN -> HALF OPEN -> CLOSED 상태로 전환됩니다 (OPEN에서 바로 CLOSED로 전환되지 않습니다)
            // 1. Circuit breaker를 OPEN 상태로 전환
            transitionToOpenState(BACKEND_B)
            // 2.Circuit breaker를 HALF_OPEN 상태로 전환
            transitionToHalfOpenState(BACKEND_B)
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.HALF_OPEN)

            // permittedNumberOfCallsInHalfOpenState = 3 으로 4회 성공하면 CLOSED 로 전환됩니다.
            repeat(4) {
                procedureMonoSuccess(BACKEND_B)
            }

            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B Mono - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // 예외가 6번 발생 (call 4회 * retry 3회) - 12회 예외
            // Backend B의 minimumNumberOfCalls = 10 < 12 회 예외
            repeat(4) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureMonoTimeout(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        private fun procedureMonoFailure(serviceName: String) {
            webClient.httpGet("/$serviceName/monoFailure", HttpStatus.INTERNAL_SERVER_ERROR)
        }

        private fun procedureMonoSuccess(serviceName: String) {
            webClient.httpGet("/$serviceName/monoSuccess")
        }

        private fun procedureMonoTimeout(serviceName: String) {
            webClient.httpGet("/$serviceName/monoTimeout")  // fallback 이 작동하므로, 항상 성공한다
        }
    }

    @Nested
    inner class FluxMethodTest {
        @Test
        fun `Backend A Flux - 연속된 예외에 CircuitBreaker 가 OPEN 됩니다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                procedureFluxFailure(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.OPEN)
        }

        @Test
        fun `Backend A Flux - 연속적으로 작업이 성공하면 Circuit breaker 는 Closed 상태로 전이됩니다`() {
            // Circuit breaker 는 OPEN -> HALF OPEN -> CLOSED 상태로 전환됩니다 (OPEN에서 바로 CLOSED로 전환되지 않습니다)
            // 1. Circuit breaker를 OPEN 상태로 전환
            transitionToOpenState(BACKEND_A)
            // 2.Circuit breaker를 HALF_OPEN 상태로 전환
            transitionToHalfOpenState(BACKEND_A)
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.HALF_OPEN)

            // permittedNumberOfCallsInHalfOpenState = 3 으로 4회 성공하면 CLOSED 로 전환됩니다.
            repeat(4) {
                procedureFluxSuccess(BACKEND_A)
            }

            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend A Flux - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // 예외가 6번 발생 (call 2회 * retry 3회) - 6회 예외
            // minimumNumberOfCalls = 5 < 6 회 예외
            repeat(2) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureFluxTimeout(BACKEND_A)
            }
            checkHealthStatus(BACKEND_A, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B Flux - 연속된 예외에 CircuitBreaker 가 OPEN 됩니다`() {
            // 예외가 6번 발생 (call 4회 * retry 3회) - 12회 예외
            // Backend B의 minimumNumberOfCalls = 10 < 12 회 예외
            repeat(4) {
                procedureFluxFailure(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.OPEN)
        }

        @Test
        fun `Backend B Flux - 연속적으로 작업이 성공하면 Circuit breaker 는 Closed 상태로 전이됩니다`() {
            // Circuit breaker 는 OPEN -> HALF OPEN -> CLOSED 상태로 전환됩니다 (OPEN에서 바로 CLOSED로 전환되지 않습니다)
            // 1. Circuit breaker를 OPEN 상태로 전환
            transitionToOpenState(BACKEND_B)
            // 2.Circuit breaker를 HALF_OPEN 상태로 전환
            transitionToHalfOpenState(BACKEND_B)
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.HALF_OPEN)

            // permittedNumberOfCallsInHalfOpenState = 3 으로 4회 성공하면 CLOSED 로 전환됩니다.
            repeat(4) {
                procedureFluxSuccess(BACKEND_B)
            }

            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        @Test
        fun `Backend B Flux - Timeout 예외 발생 시 Circuit Breaker Fallback 이 작동하여 성공으로 간주한다`() {
            // 예외가 6번 발생 (call 4회 * retry 3회) - 12회 예외
            // Backend B의 minimumNumberOfCalls = 10 < 12 회 예외
            repeat(4) {
                // fallback 이 작동하므로, 항상 성공한다
                procedureFluxTimeout(BACKEND_B)
            }
            checkHealthStatus(BACKEND_B, CircuitBreaker.State.CLOSED)
        }

        private fun procedureFluxFailure(serviceName: String) {
            webClient.httpGet("/$serviceName/fluxFailure", HttpStatus.INTERNAL_SERVER_ERROR)
        }

        private fun procedureFluxSuccess(serviceName: String) {
            webClient.httpGet("/$serviceName/fluxSuccess")
        }

        private fun procedureFluxTimeout(serviceName: String) {
            webClient.httpGet("/$serviceName/fluxTimeout")  // fallback 이 작동하므로, 항상 성공한다
        }
    }
}
