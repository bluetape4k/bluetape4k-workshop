package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CommandFingerprintTest {
    @Test
    fun `request fingerprint ignores field order but not semantic values`() {
        val first = CommandFingerprint.request("meter-register", mapOf("unit" to "request", "code" to "api_calls"))
        val reordered = CommandFingerprint.request("meter-register", mapOf("code" to "api_calls", "unit" to "request"))
        val changed = CommandFingerprint.request("meter-register", mapOf("code" to "api_calls", "unit" to "call"))

        assertEquals(first, reordered)
        assertNotEquals(first, changed)
    }
}
