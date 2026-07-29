package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import org.junit.jupiter.api.Test

internal class IdempotencyFingerprintTest {
    @Test
    fun `canonical request fingerprint ignores map insertion order`() {
        val first = IdempotencyFingerprint.request(mapOf("sku-b" to 2, "sku-a" to 1))
        val second = IdempotencyFingerprint.request(linkedMapOf("sku-a" to 1, "sku-b" to 2))

        first shouldBeEqualTo second
    }

    @Test
    fun `different payload has different fingerprint`() {
        IdempotencyFingerprint.request(mapOf("sku-a" to 1)) shouldNotBeEqualTo
            IdempotencyFingerprint.request(mapOf("sku-a" to 2))
    }

    @Test
    fun `raw idempotency key is reduced to fixed length hash`() {
        IdempotencyFingerprint.key("customer-visible-secret").length shouldBeEqualTo 64
        IdempotencyFingerprint.key("customer-visible-secret") shouldNotBeEqualTo "customer-visible-secret"
    }
}
