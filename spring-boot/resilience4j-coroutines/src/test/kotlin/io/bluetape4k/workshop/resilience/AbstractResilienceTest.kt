package io.bluetape4k.workshop.resilience

import io.bluetape4k.exceptions.NotSupportedException
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
abstract class AbstractResilienceTest {

    companion object: KLogging() {
        const val BACKEND_A = "backendA"
        const val BACKEND_B = "backendB"
        const val BACKEND_C = "backendC"
    }

    @Autowired
    protected val circuitBreakerRegistry: CircuitBreakerRegistry = uninitialized()

    @Autowired
    protected val webClient: WebTestClient = uninitialized()


    @BeforeEach
    fun setup() {
        log.debug { "모든 Circuit Breaker 상태를 Closed로 초기화 합니다." }
        transitionToClosedState(BACKEND_A)
        transitionToClosedState(BACKEND_B)
    }

    protected fun transitionToOpenState(circuitBreakerName: String) {
        transitionCircuitBreakerState(circuitBreakerName, CircuitBreaker.State.OPEN)
    }

    protected fun transitionToClosedState(circuitBreakerName: String) {
        transitionCircuitBreakerState(circuitBreakerName, CircuitBreaker.State.CLOSED)
    }

    protected fun transitionToHalfOpenState(circuitBreakerName: String) {
        transitionCircuitBreakerState(circuitBreakerName, CircuitBreaker.State.HALF_OPEN)
    }

    protected fun transitionCircuitBreakerState(name: String, state: CircuitBreaker.State) {
        circuitBreakerRegistry.circuitBreaker(name).apply {
            when (state) {
                CircuitBreaker.State.OPEN      -> this.transitionToOpenState()
                CircuitBreaker.State.CLOSED    -> this.transitionToClosedState()
                CircuitBreaker.State.HALF_OPEN -> this.transitionToHalfOpenState()
                else                           -> throw NotSupportedException("Not supported state: $state")
            }
        }
    }
}
