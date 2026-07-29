package io.bluetape4k.workshop.commerce.metering.eventsourcing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class EventSourcingRuntimeContractTest {
    @Test
    fun `advanced billing example runs on Java 25 without preview`() {
        assertEquals(25, Runtime.version().feature())
        assertFalse(ManagementFactory.getRuntimeMXBean().inputArguments.contains("--enable-preview"))
    }
}
