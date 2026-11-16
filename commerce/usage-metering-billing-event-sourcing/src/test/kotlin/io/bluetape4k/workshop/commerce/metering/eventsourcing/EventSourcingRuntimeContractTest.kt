package io.bluetape4k.workshop.commerce.metering.eventsourcing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class EventSourcingRuntimeContractTest {

    companion object: KLogging()
    
    @Test
    fun `advanced billing example runs on Java 25 without preview`() {
        Runtime.version().feature().shouldBeEqualTo(25)
        ManagementFactory.getRuntimeMXBean().inputArguments.contains("--enable-preview").shouldBeTrue()
    }
}
