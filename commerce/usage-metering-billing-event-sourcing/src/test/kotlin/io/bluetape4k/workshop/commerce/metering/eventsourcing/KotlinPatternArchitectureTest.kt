package io.bluetape4k.workshop.commerce.metering.eventsourcing

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class KotlinPatternArchitectureTest {
    @Test
    fun `Spring Boot application entry point is explicit`() {
        assertNotNull(
            Class.forName(
                "io.bluetape4k.workshop.commerce.metering.eventsourcing.UsageBillingEventSourcingApplicationKt",
            ),
        )
    }
}
