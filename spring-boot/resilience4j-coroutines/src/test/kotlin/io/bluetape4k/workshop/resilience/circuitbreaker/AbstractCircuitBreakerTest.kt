package io.bluetape4k.workshop.resilience.circuitbreaker

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.bluetape4k.assertions.shouldBeEqualTo

abstract class AbstractCircuitBreakerTest: AbstractResilienceTest() {

    companion object: KLoggingChannel()

    protected fun checkHealthStatus(circuitBreakerName: String, expectedState: CircuitBreaker.State) {
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName)
        circuitBreaker.state shouldBeEqualTo expectedState
    }
}
