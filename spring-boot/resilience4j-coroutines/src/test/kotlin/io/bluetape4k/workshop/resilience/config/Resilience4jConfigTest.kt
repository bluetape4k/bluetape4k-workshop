package io.bluetape4k.workshop.resilience.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class Resilience4jConfigTest: AbstractResilienceTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        circuitBreakerRegistry.shouldNotBeNull()

        circuitBreakerRegistry.circuitBreaker(BACKEND_A).shouldNotBeNull()
        circuitBreakerRegistry.circuitBreaker(BACKEND_B).shouldNotBeNull()
        circuitBreakerRegistry.circuitBreaker(BACKEND_C).shouldNotBeNull()
    }
}
