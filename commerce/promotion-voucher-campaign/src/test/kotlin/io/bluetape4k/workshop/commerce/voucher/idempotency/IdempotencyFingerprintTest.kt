package io.bluetape4k.workshop.commerce.voucher.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal

internal class IdempotencyFingerprintTest {
    @Test
    fun `header case field order and schema default normalize to one fingerprint`() {
        val first =
            fingerprint(
                headers = mapOf("Content-Type" to " application/json "),
                body = """{"userRef":"u1"}""",
            )
        val second =
            fingerprint(
                method = "post",
                path = "/api/v1/campaigns/c-1/vouchers/",
                headers = mapOf("content-type" to "application/json", "X-Ignored" to "not-semantic"),
                body = """{"forceReview":false,"userRef":"u1"}""",
            )

        first shouldBeEqualTo second
    }

    @Test
    fun `canonical decimal key order and nullable omission follow the closed schema`() {
        val first =
            fingerprint(
                body = """{"amount":1.00,"note":null,"userRef":"u1"}""",
            )
        val second =
            fingerprint(
                body = """{"userRef":"u1","amount":1,"forceReview":false}""",
            )

        first shouldBeEqualTo second
    }

    @Test
    fun `same key with a semantic payload difference has a different fingerprint`() {
        fingerprint(body = """{"userRef":"u1"}""") shouldNotBeEqualTo
            fingerprint(body = """{"userRef":"u2"}""")
    }

    @Test
    fun `raw idempotency key is domain separated by tenant principal operation and resource`() {
        val principal = Digest.sha256("principal-a")
        val first = IdempotencyFingerprint.key("tenant-a", principal, "ALLOCATE", "campaign-1", "raw-key-123")
        val otherTenant = IdempotencyFingerprint.key("tenant-b", principal, "ALLOCATE", "campaign-1", "raw-key-123")
        val otherOperation = IdempotencyFingerprint.key("tenant-a", principal, "REDEEM", "campaign-1", "raw-key-123")

        first shouldNotBeEqualTo otherTenant
        first shouldNotBeEqualTo otherOperation
        first.base64Url.contains("raw-key-123") shouldBeEqualTo false
    }

    @Test
    fun `unknown request properties are rejected before hashing`() {
        assertFailsWith<IllegalArgumentException> {
            fingerprint(body = """{"userRef":"u1","fixtureOverride":"approve"}""")
        }
    }

    @Test
    fun `missing required request property is rejected before hashing`() {
        assertFailsWith<IllegalArgumentException> {
            fingerprint(body = """{"forceReview":false}""")
        }
    }

    @Test
    fun `golden request fixture is stable and uses unpadded base64url`() {
        val digest = fingerprint(body = """{"amount":1.00,"userRef":"u1"}""")

        digest.base64Url shouldBeEqualTo "bstRVOnLhqvLrUzLUwckQDSUtGTyEnowlYcsQPXSzds"
    }

    private fun fingerprint(
        method: String = "POST",
        path: String = "/api/v1/campaigns/c-1/vouchers",
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
        body: String,
    ): Digest =
        IdempotencyFingerprint.request(
            method = method,
            path = path,
            resourceId = "c-1",
            headers = headers,
            body = body,
            schema =
                ClosedRequestSchema(
                    fields =
                        mapOf(
                            "amount" to CanonicalField.Decimal(default = BigDecimal.ONE),
                            "forceReview" to CanonicalField.Boolean(default = false),
                            "note" to CanonicalField.Text(nullable = true, nullEquivalentToOmitted = true),
                            "userRef" to CanonicalField.Text(),
                        ),
                ),
        )
}
