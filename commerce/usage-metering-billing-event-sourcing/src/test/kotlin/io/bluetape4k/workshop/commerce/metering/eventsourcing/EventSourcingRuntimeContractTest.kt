package io.bluetape4k.workshop.commerce.metering.eventsourcing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class EventSourcingRuntimeContractTest {
    @Test
    fun `advanced billing example runs on Java 25 without preview`() {
        Runtime.version().feature().shouldBeEqualTo(25)
        ManagementFactory.getRuntimeMXBean().inputArguments.contains("--enable-preview").shouldBeFalse()
    }
}
