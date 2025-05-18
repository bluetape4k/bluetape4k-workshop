package io.bluetape4k.workshop.observation.examples

import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.observation.AbstractObservationTest
import io.bluetape4k.workshop.observation.service.GreetingService
import io.micrometer.observation.ObservationRegistry
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ObservationRegistryTest: AbstractObservationTest() {

    @Autowired
    private val observationRegistry: ObservationRegistry = uninitialized()

    @Autowired
    private val greetingService: GreetingService = uninitialized()

    @Test
    fun `context loading`() {
        observationRegistry.shouldNotBeNull()
        greetingService.shouldNotBeNull()
    }

}
