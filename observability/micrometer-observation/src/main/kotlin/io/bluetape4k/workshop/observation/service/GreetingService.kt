package io.bluetape4k.workshop.observation.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.observation.support.observeOrNull
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.annotation.Observed
import org.springframework.stereotype.Service
import java.util.function.Supplier

@Observed(name = "greetingService")
@Service
class GreetingService(private val observationRegistry: ObservationRegistry) {

    companion object: KLogging() {
        const val GREETING_SERVICE_NAME = "greetingService"
    }

    private val greetingObservation: Observation by lazy {
        log.info { "Create Observation. $GREETING_SERVICE_NAME" }
        Observation.createNotStarted(GREETING_SERVICE_NAME, observationRegistry)
    }

    fun sayHello(): String {
        return greetingObservation.observe(Supplier { sayHelloInternal() })!!
    }

    fun sayHelloWithName(name: String): String {
        return Observation.createNotStarted("$GREETING_SERVICE_NAME.sayHelloWithName", observationRegistry)
            .contextualName("sayHello-with-name")
            .lowCardinalityKeyValue("name", name)
            .highCardinalityKeyValue("requestId", "1234")
            .observeOrNull { "Hello, $name" }!!
    }

    private fun sayHelloInternal(): String {
        log.debug { "call sayHelloInternal" }
        return "Hello, World!"
    }
}
