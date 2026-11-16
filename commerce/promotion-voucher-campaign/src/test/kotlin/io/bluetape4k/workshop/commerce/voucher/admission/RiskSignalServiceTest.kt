package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import org.junit.jupiter.api.Test

internal class RiskSignalServiceTest {

    companion object: KLogging()

    @Test
    fun `versioned HMAC keys are deterministic and never expose raw identity`() {
        val keys = keys()

        val first = keys.rateKey("tenant-a", "user@example.com", "allocate")
        val second = keys.rateKey("tenant-a", "user@example.com", "allocate")

        first shouldBeEqualTo second
        first shouldStartWith "v7:"
        first shouldNotContain "tenant-a"
        first shouldNotContain "user@example.com"
    }

    @Test
    fun `Bloom positive requests review while backend failure remains unknown`() {
        val backend = RecordingRiskBackend()
        val service = RiskSignalService(keys(), backend)

        service.assess("tenant-a", "subject-1") shouldBeEqualTo RiskSignal.CLEAR
        service.remember("tenant-a", "subject-1").shouldBeTrue()
        service.assess("tenant-a", "subject-1") shouldBeEqualTo RiskSignal.REVIEW

        backend.failure = IllegalStateException("redis unavailable")
        service.assess("tenant-a", "subject-1") shouldBeEqualTo RiskSignal.UNKNOWN
    }

    private class RecordingRiskBackend: VoucherRiskBackend {
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

    private fun keys(): VoucherAdmissionKeyFactory =
        VoucherAdmissionKeyFactory(
            version = 7,
            rateKey = ByteArray(32) { 0x31 },
            riskKey = ByteArray(32) { 0x52 },
        )
}
