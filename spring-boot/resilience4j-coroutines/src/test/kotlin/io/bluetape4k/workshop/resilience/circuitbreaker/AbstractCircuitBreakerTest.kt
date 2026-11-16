package io.bluetape4k.workshop.resilience.circuitbreaker

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import io.github.resilience4j.circuitbreaker.CircuitBreaker

abstract class AbstractCircuitBreakerTest: AbstractResilienceTest() {

    companion object: KLoggingChannel()

    protected fun checkHealthStatus(circuitBreakerName: String, expectedState: CircuitBreaker.State) {
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName)
        log.debug { "Circuit Breaker name=${circuitBreaker.name}, state=${circuitBreaker.state}" }
        circuitBreaker.state shouldBeEqualTo expectedState
    }
}
