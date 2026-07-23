package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

@Tag("integration")
class PoisonIntegrationTest {
    @Test
    fun `poison event is quarantined while an independent aggregate progresses and records a redrive request`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()
            fixture.sendQueryEvent(POISON_EVENT_ID, TENANT, "UnsupportedInvoiceFact")
            await().atMost(TIMEOUT).untilAsserted {
                fixture.queryRecoverySnapshot().quarantineCount shouldBeEqualTo 1L
            }

            fixture.sendQueryEvent(VALID_EVENT_ID, TENANT, "InvoiceIssued")
            await().atMost(TIMEOUT).untilAsserted {
                fixture.queryAppliedEventCount() shouldBeEqualTo 1
            }
            fixture.redriveQueryEvent(POISON_EVENT_ID) shouldBeEqualTo true
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        val POISON_EVENT_ID: UUID = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1b01")
        val VALID_EVENT_ID: UUID = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1b02")
        const val TENANT = "tenant-poison"
    }
}
