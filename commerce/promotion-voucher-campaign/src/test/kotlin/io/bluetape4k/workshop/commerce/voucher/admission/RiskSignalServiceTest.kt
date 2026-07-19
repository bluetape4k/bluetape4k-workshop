package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import org.junit.jupiter.api.Test

internal class RiskSignalServiceTest {
    @Test
    fun `versioned HMAC keys are deterministic and never expose raw identity`() {
        val keys = keys()

        val first = keys.rateKey("tenant-a", "user@example.com", "allocate")
        val second = keys.rateKey("tenant-a", "user@example.com", "allocate")

        first shouldBeEqualTo second
        first.startsWith("v7:") shouldBeEqualTo true
        first.contains("tenant-a") shouldBeEqualTo false
        first.contains("user@example.com") shouldBeEqualTo false
    }

    @Test
    fun `Bloom positive requests review while backend failure remains unknown`() {
        val backend = RecordingRiskBackend()
        val service = RiskSignalService(keys(), backend)

        service.assess("tenant-a", "subject-1") shouldBeEqualTo RiskSignal.CLEAR
        service.remember("tenant-a", "subject-1") shouldBeEqualTo true
        service.assess("tenant-a", "subject-1") shouldBeEqualTo RiskSignal.REVIEW

        backend.failure = IllegalStateException("redis unavailable")
        service.assess("tenant-a", "subject-1") shouldBeEqualTo RiskSignal.UNKNOWN
    }

    private class RecordingRiskBackend : VoucherRiskBackend {
        private val values = mutableSetOf<String>()
        var failure: RuntimeException? = null

        override fun add(digest: String) {
            failure?.let { throw it }
            values += digest
        }

        override fun mightContain(digest: String): Boolean {
            failure?.let { throw it }
            return digest in values
        }
    }

    private fun keys() =
        VoucherAdmissionKeyFactory(
            version = 7,
            rateKey = ByteArray(32) { 0x31 },
            riskKey = ByteArray(32) { 0x52 },
        )
}
