package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import org.junit.jupiter.api.Test

class CommandFingerprintTest {
    @Test
    fun `request fingerprint ignores field order but not semantic values`() {
        val first = CommandFingerprint.request("meter-register", mapOf("unit" to "request", "code" to "api_calls"))
        val reordered = CommandFingerprint.request("meter-register", mapOf("code" to "api_calls", "unit" to "request"))
        val changed = CommandFingerprint.request("meter-register", mapOf("code" to "api_calls", "unit" to "call"))

        reordered.shouldBeEqualTo(first)
        changed.shouldNotBeEqualTo(first)
    }
}
