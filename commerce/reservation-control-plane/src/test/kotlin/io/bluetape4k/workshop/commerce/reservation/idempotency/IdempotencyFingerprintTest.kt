package io.bluetape4k.workshop.commerce.reservation.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import org.junit.jupiter.api.Test

internal class IdempotencyFingerprintTest {
    @Test
    fun `key digest is deterministic and separated by tenant and operation`() {
        val digest = IdempotencyFingerprint.key("tenant-a", "create-hold", "customer-visible-secret")

        digest.length shouldBeEqualTo 64
        digest shouldBeEqualTo IdempotencyFingerprint.key("tenant-a", "create-hold", "customer-visible-secret")
        digest shouldNotBeEqualTo IdempotencyFingerprint.key("tenant-b", "create-hold", "customer-visible-secret")
        digest shouldNotBeEqualTo IdempotencyFingerprint.key("tenant-a", "confirm-hold", "customer-visible-secret")
        digest shouldNotBeEqualTo "customer-visible-secret"
    }

    @Test
    fun `request fingerprint is operation scoped`() {
        val create = IdempotencyFingerprint.request("create-hold", "{\"resourceId\":\"room-a\"}")

        create shouldBeEqualTo
            IdempotencyFingerprint.request("create-hold", "{\"resourceId\":\"room-a\"}")
        create shouldNotBeEqualTo
            IdempotencyFingerprint.request("confirm-hold", "{\"resourceId\":\"room-a\"}")
    }
}
