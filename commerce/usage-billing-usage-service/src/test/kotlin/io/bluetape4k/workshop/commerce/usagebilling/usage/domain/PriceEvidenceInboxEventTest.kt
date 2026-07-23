package io.bluetape4k.workshop.commerce.usagebilling.usage.domain

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PriceEvidenceInboxEventTest {
    @Test
    fun `tenant mismatch is rejected as producer input`() {
        assertFailsWith<IllegalArgumentException> {
            PriceEvidenceInboxEvent(
                eventId = UUID.randomUUID(),
                tenantId = "tenant-a",
                payloadDigest = "a".repeat(64),
                evidence = PriceEvidence(
                    tenantId = "tenant-b",
                    meterCode = "api_calls",
                    currency = "USD",
                    unitPrice = BigDecimal("0.10"),
                    effectiveAt = Instant.parse("2026-07-23T00:00:00Z"),
                ),
            )
        }
    }
}
