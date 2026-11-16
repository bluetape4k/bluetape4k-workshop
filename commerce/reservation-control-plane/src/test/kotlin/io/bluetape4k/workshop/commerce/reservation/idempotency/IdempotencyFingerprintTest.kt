package io.bluetape4k.workshop.commerce.reservation.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

internal class IdempotencyFingerprintTest {

    companion object: KLogging()

    @Test
    fun `key digest is deterministic and separated by tenant and operation`() {
        val digest = IdempotencyFingerprint.key(
            tenantId = "tenant-a",
            operation = "create-hold",
            rawKey = "customer-visible-secret"
        )

        digest.length shouldBeEqualTo 64

        digest shouldBeEqualTo IdempotencyFingerprint.key(
            tenantId = "tenant-a",
            operation = "create-hold",
            rawKey = "customer-visible-secret"
        )
        digest shouldNotBeEqualTo IdempotencyFingerprint.key(
            tenantId = "tenant-b",
            operation = "create-hold",
            rawKey = "customer-visible-secret"
        )
        digest shouldNotBeEqualTo IdempotencyFingerprint.key(
            tenantId = "tenant-a",
            operation = "confirm-hold",
            rawKey = "customer-visible-secret"
        )

        digest shouldNotBeEqualTo "customer-visible-secret"
        digest shouldBeEqualTo "c8c08a6aaf042cca74e5079aff177fbb55b4b93b5c7f00fff0a274f57c46fd00"
    }

    @Test
    fun `request fingerprint is operation scoped`() {
        val create = IdempotencyFingerprint.request(
            operation = "create-hold",
            canonicalPayload = "{\"resourceId\":\"room-a\"}"
        )

        create shouldBeEqualTo IdempotencyFingerprint.request(
            operation = "create-hold",
            canonicalPayload = "{\"resourceId\":\"room-a\"}"
        )
        create shouldNotBeEqualTo IdempotencyFingerprint.request(
            operation = "confirm-hold",
            canonicalPayload = "{\"resourceId\":\"room-a\"}"
        )
        create shouldBeEqualTo "742ef157b884b48ed4570007635cafb4f48a001e60c95714cab7b6a5fbd94ed5"
    }

    @Test
    fun `unicode와 빈 field는 domain NUL framing을 유지한다`() {
        IdempotencyFingerprint.key(
            tenantId = "테넌트-a",
            operation = "create-hold",
            rawKey = ""
        ) shouldBeEqualTo "d4a37e2e79bf58dcc917efd8a200d290f0e6e9f85d0187f1090f32ccf79f2b1e"

        IdempotencyFingerprint.request(
            operation = "create-hold",
            canonicalPayload = "소유자=고객🙂"
        ) shouldBeEqualTo "e12be6d71fd10a584c8235b7b0a33e2aa5f3d1c8455821af836c186b340092fe"
    }
}
