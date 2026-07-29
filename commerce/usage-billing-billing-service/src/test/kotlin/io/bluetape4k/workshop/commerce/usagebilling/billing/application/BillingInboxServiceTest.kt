package io.bluetape4k.workshop.commerce.usagebilling.billing.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidence
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class BillingInboxServiceTest {
    private val journal = InMemoryBillingInboxJournal()
    private val service = BillingInboxService(journal)

    @Test
    fun `expected aggregate version is applied exactly once`() {
        val event = usageEvent(version = 1)

        service.handle(event) shouldBeEqualTo BillingInboxOutcome.APPLIED
        service.handle(event) shouldBeEqualTo BillingInboxOutcome.DUPLICATE
        journal.appliedEventIds.size shouldBeEqualTo 1
    }

    @Test
    fun `future aggregate version is deferred until its predecessor arrives`() {
        service.handle(usageEvent(version = 2)) shouldBeEqualTo BillingInboxOutcome.DEFERRED

        service.handle(usageEvent(version = 1)) shouldBeEqualTo BillingInboxOutcome.APPLIED
        service.handle(usageEvent(version = 2)) shouldBeEqualTo BillingInboxOutcome.APPLIED
    }

    @Test
    fun `same event identifier with a different digest is quarantined`() {
        val eventId = UUID.fromString("2c8ee8f1-5f56-4fc9-9f24-21542b46b8b0")
        service.handle(usageEvent(eventId = eventId, digest = "digest-a")) shouldBeEqualTo BillingInboxOutcome.APPLIED

        service.handle(usageEvent(eventId = eventId, digest = "digest-b")) shouldBeEqualTo
            BillingInboxOutcome.QUARANTINED
    }

    @Test
    fun `usage without local pricing evidence remains deferred`() {
        service.handle(usageEvent(meterCode = "unknown_meter")) shouldBeEqualTo BillingInboxOutcome.DEFERRED
    }

    private fun usageEvent(
        eventId: UUID = UUID.randomUUID(),
        version: Long = 1,
        digest: String = "digest-$version",
        meterCode: String = "api_calls",
    ): BillingInboxEvent =
        BillingInboxEvent(eventId, "tenant-a", "Usage", "usage-1", version, digest, meterCode)

    private class InMemoryBillingInboxJournal : BillingInboxJournal {
        override val appliedEventIds = mutableSetOf<UUID>()
        private val digests = mutableMapOf<UUID, String>()
        private var expectedVersion = 1L

        override fun priceEvidence(tenantId: String, meterCode: String, currency: String): BillingPriceEvidence? =
            BillingPriceEvidence(tenantId, meterCode, currency, BigDecimal("0.10"), Instant.EPOCH)
                .takeIf { meterCode == "api_calls" }

        override fun digestFor(eventId: UUID): String? = digests[eventId]

        override fun expectedVersion(tenantId: String, aggregateId: String): Long = expectedVersion

        override fun apply(event: BillingInboxEvent) {
            digests[event.eventId] = event.payloadDigest
            appliedEventIds += event.eventId
            expectedVersion += 1
        }
    }
}
