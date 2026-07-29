package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

@Tag("integration")
class SchemaEvolutionIntegrationTest {
    @Test
    fun `Query accepts additive v2 and quarantines an unknown mandatory version`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            val compatibleEventId = UUID.randomUUID()
            val unsupportedEventId = UUID.randomUUID()

            fixture.sendQueryEvent(compatibleEventId, TENANT, "InvoiceIssued", schemaVersion = 2)
            fixture.sendQueryEvent(unsupportedEventId, TENANT, "InvoiceIssued", schemaVersion = 99)

            await().pollInterval(Duration.ofMillis(250)).atMost(TIMEOUT).untilAsserted {
                fixture.queryAppliedEventCount() shouldBeEqualTo 1
                fixture.queryRecoverySnapshot().quarantineCount shouldBeEqualTo 1L
            }
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        const val TENANT = "tenant-schema"
    }
}
