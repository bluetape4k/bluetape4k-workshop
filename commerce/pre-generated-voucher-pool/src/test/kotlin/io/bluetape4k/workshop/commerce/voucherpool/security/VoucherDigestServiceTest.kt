package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import org.junit.jupiter.api.Test
import java.util.UUID

internal class VoucherDigestServiceTest {
    @Test
    fun `stable dedup ignores campaign and verification key rotation`() {
        val code = CanonicalVoucherCode.of("POOL-001")
        val before = digestService(verificationVersion = 1)
        val after = digestService(verificationVersion = 2, retainVerificationVersion = 1)

        val stableBefore = before.stableDedup(TENANT, code)
        stableBefore shouldBeEqualTo after.stableDedup(TENANT, code)
        stableBefore.keyVersion shouldBeEqualTo 7
        before.commandTombstone(TENANT, "reserve", "request-1") shouldBeEqualTo
            after.commandTombstone(TENANT, "reserve", "request-1")
        before.commandTombstoneKeyVersion shouldBeEqualTo 4
        after.commandTombstoneKeyVersion shouldBeEqualTo 4

        val oldVerification = before.verification(TENANT, CAMPAIGN, ALLOCATION, code)
        val currentVerification = after.verification(TENANT, CAMPAIGN, ALLOCATION, code)
        (oldVerification == currentVerification).shouldBeFalse()
        after.matchesVerification(TENANT, CAMPAIGN, ALLOCATION, code, oldVerification).shouldBeTrue()
    }

    @Test
    fun `retained user identity key preserves ownership and limit identity during rotation`() {
        val before = digestService(verificationVersion = 1, userIdentityVersion = 1)
        val after = digestService(
            verificationVersion = 1,
            userIdentityVersion = 2,
            retainUserIdentityVersion = 1,
        )

        val original = before.userIdentity(TENANT, CAMPAIGN, "customer-a")
        (original == after.userIdentity(TENANT, CAMPAIGN, "customer-a")).shouldBeFalse()
        original shouldBeEqualTo after.userIdentity(TENANT, CAMPAIGN, "customer-a", original.keyVersion)
    }

    @Test
    fun `purpose and scope are part of the canonical digest`() {
        val service = digestService(verificationVersion = 1)
        val code = CanonicalVoucherCode.of("same-material")

        (service.stableDedup(TENANT, code) == service.stableDedup("other-tenant", code)).shouldBeFalse()
        (
            service.userIdentity(TENANT, CAMPAIGN, "same-material") ==
                service.commandTombstone(TENANT, "reserve", "same-material")
        ).shouldBeFalse()
        (
            service.verification(TENANT, CAMPAIGN, ALLOCATION, code) ==
                service.verification(TENANT, UUID.randomUUID(), ALLOCATION, code)
        ).shouldBeFalse()
    }

    @Test
    fun `unknown digest key version fails closed without exposing material`() {
        val service = digestService(verificationVersion = 2, retainVerificationVersion = 1)
        val unknown = VoucherDigest.of(DigestPurpose.VERIFICATION, 99, ByteArray(32) { 9 })

        val failure = assertFailsWith<VoucherKeyMaterialUnavailableException> {
            service.matchesVerification(
                TENANT,
                CAMPAIGN,
                ALLOCATION,
                CanonicalVoucherCode.of("TOP-SECRET"),
                unknown,
            )
        }
        failure.message.orEmpty() shouldNotContain "TOP-SECRET"
        unknown.toString() shouldNotContain "09"
    }

    private fun digestService(
        verificationVersion: Int,
        retainVerificationVersion: Int? = null,
        userIdentityVersion: Int = 3,
        retainUserIdentityVersion: Int? = null,
    ): VoucherDigestService {
        val verificationKeys = buildList {
            add(DigestKey.of(verificationVersion, keyBytes(verificationVersion)))
            retainVerificationVersion?.let { add(DigestKey.of(it, keyBytes(it))) }
        }
        return VoucherDigestService(
            stableDedupKey = DigestKey.of(7, keyBytes(7)),
            commandTombstoneKey = DigestKey.of(4, keyBytes(4)),
            rotatingKeys = mapOf(
                DigestPurpose.VERIFICATION to DigestKeyRing.of(verificationKeys.first(), verificationKeys.drop(1)),
                DigestPurpose.USER_IDENTITY to DigestKeyRing.of(
                    DigestKey.of(userIdentityVersion, keyBytes(userIdentityVersion)),
                    retainUserIdentityVersion?.let { listOf(DigestKey.of(it, keyBytes(it))) }.orEmpty(),
                ),
                DigestPurpose.REDIS_SIGNAL to DigestKeyRing.of(DigestKey.of(5, keyBytes(5))),
                DigestPurpose.AUDIT to DigestKeyRing.of(DigestKey.of(6, keyBytes(6))),
            ),
        )
    }

    private fun keyBytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    companion object {
        private const val TENANT = "tenant-a"
        private val CAMPAIGN: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val ALLOCATION: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
