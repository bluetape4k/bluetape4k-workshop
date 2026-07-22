package io.bluetape4k.workshop.commerce.voucherpool.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

internal class VoucherPoolFingerprintTest {
    @Test
    fun `semantic fields are canonical regardless of map iteration order`() {
        val first = VoucherPoolFingerprint.command(
            operation = "reserve",
            fields = linkedMapOf("campaignId" to "campaign-1", "quantity" to "1"),
        )
        val second = VoucherPoolFingerprint.command(
            operation = "reserve",
            fields = linkedMapOf("quantity" to "1", "campaignId" to "campaign-1"),
        )

        first shouldBeEqualTo second
    }

    @Test
    fun `length prefixing separates ambiguous field material`() {
        val first = VoucherPoolFingerprint.command("import", mapOf("ab" to "c", "d" to "e"))
        val second = VoucherPoolFingerprint.command("import", mapOf("a" to "bc", "d" to "e"))

        (first == second).shouldBeFalse()
    }

    @Test
    fun `operation null and omitted fields remain distinct`() {
        val presentNull = VoucherPoolFingerprint.command("reserve", mapOf("user" to null))
        val omitted = VoucherPoolFingerprint.command("reserve", emptyMap())
        val otherOperation = VoucherPoolFingerprint.command("allocate", mapOf("user" to null))

        (presentNull == omitted).shouldBeFalse()
        (presentNull == otherOperation).shouldBeFalse()
    }

    @Test
    fun `fingerprint is immutable redacted and bounded`() {
        val fields = linkedMapOf("campaignId" to "campaign-1")
        val fingerprint = VoucherPoolFingerprint.command("reserve", fields)
        fields["campaignId"] = "mutated"
        val copied = fingerprint.copyBytes().also { it.fill(0) }

        fingerprint shouldBeEqualTo VoucherPoolFingerprint.command("reserve", mapOf("campaignId" to "campaign-1"))
        fingerprint.toString() shouldNotContain "campaign-1"
        copied.contentEquals(fingerprint.copyBytes()).shouldBeFalse()
        assertFailsWith<IllegalArgumentException> {
            VoucherPoolFingerprint.command("reserve", mapOf("payload" to "x".repeat(4_097)))
        }
    }

    @Test
    fun `owner retains only defensive copies of capability digests`() {
        val scopedKeyDigest = ByteArray(32) { it.toByte() }
        val capabilityDigest = ByteArray(32) { (it + 1).toByte() }
        val expectedCapability = capabilityDigest.copyOf()
        val owner = IdempotencyOwner(
            scope = CommandScope("tenant-a", "reserve"),
            scopedKeyDigest = scopedKeyDigest,
            keyVersion = 4,
            fingerprint = VoucherPoolFingerprint.command("reserve", mapOf("campaign" to UUID.randomUUID().toString())),
            ownerTokenDigest = capabilityDigest,
        )

        scopedKeyDigest.fill(0)
        capabilityDigest.fill(0)
        owner.copyTokenDigest().contentEquals(expectedCapability).shouldBeTrue()
        owner.copyScopedKeyDigest().all { it == 0.toByte() }.shouldBeFalse()
        owner.javaClass.declaredFields.none { it.type == String::class.java }.shouldBeTrue()
    }
}
