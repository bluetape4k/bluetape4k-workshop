package io.bluetape4k.workshop.commerce.metering.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CommandFingerprintTest {

    @Test
    fun `field order does not change a domain separated fingerprint`() {
        val first =
            CommandFingerprint.request(
                "usage-ingest",
                mapOf("quantity" to "1.0", "occurredAt" to "2026-07-01T00:00:00Z"),
            )
        val second =
            CommandFingerprint.request(
                "usage-ingest",
                linkedMapOf("occurredAt" to "2026-07-01T00:00:00Z", "quantity" to "1.0"),
            )

        first shouldBeEqualTo second
        first.value.length shouldBeEqualTo 64
    }

    @Test
    fun `decimal and instant canonicalization is stable`() {
        CommandFingerprint.decimal(BigDecimal("1.2300")) shouldBeEqualTo "1.23"
        CommandFingerprint.decimal(BigDecimal("0.000")) shouldBeEqualTo "0"
        CommandFingerprint.instant(Instant.parse("2026-07-01T00:00:00.000Z")) shouldBeEqualTo
            "2026-07-01T00:00:00Z"
    }

    @Test
    fun `raw key is reduced to a non-reversible digest`() {
        val rawKey = "customer-visible-secret-key"
        val digest = CommandFingerprint.key(rawKey)

        digest.value.length shouldBeEqualTo 64
        (digest.value == rawKey) shouldBeEqualTo false
    }
}
