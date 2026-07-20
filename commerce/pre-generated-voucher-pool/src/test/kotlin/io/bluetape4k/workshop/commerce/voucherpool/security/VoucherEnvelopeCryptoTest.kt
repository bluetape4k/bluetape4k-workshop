package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

internal class VoucherEnvelopeCryptoTest {
    @Test
    fun `AES GCM envelope round trip uses unique nonces and redacted values`() {
        val crypto = crypto(kekVersion = "k1")
        val code = CanonicalVoucherCode.of("POOL-ROUND-TRIP")
        val stableDigest = digests.stableDedup(IDENTITY.tenantId, code)
        val codeNonces = mutableSetOf<String>()
        val wrapNonces = mutableSetOf<String>()

        repeat(64) {
            val encrypted = crypto.encrypt(IDENTITY, code)
            crypto.decryptAndVerify(IDENTITY, encrypted, stableDigest) shouldBeEqualTo code
            codeNonces += Base64.getEncoder().encodeToString(encrypted.copyCodeNonce())
            wrapNonces += Base64.getEncoder().encodeToString(encrypted.copyWrapNonce())
            encrypted.toString().contains("POOL-ROUND-TRIP") shouldBeEqualTo false
        }

        codeNonces.size shouldBeEqualTo 64
        wrapNonces.size shouldBeEqualTo 64
    }

    @Test
    fun `unknown key tag failure digest mismatch and row swap fail closed`() {
        val code = CanonicalVoucherCode.of("POOL-TAMPER")
        val encrypted = crypto(kekVersion = "k1").encrypt(IDENTITY, code)
        val stableDigest = digests.stableDedup(IDENTITY.tenantId, code)

        assertFailsWith<VoucherCryptoException> {
            crypto(kekVersion = "k2").decryptAndVerify(IDENTITY, encrypted, stableDigest)
        }.reason shouldBeEqualTo VoucherCryptoFailureReason.UNKNOWN_KEY_VERSION

        val tamperedCiphertext = encrypted.copyCodeCiphertext().also { it[it.lastIndex] = (it.last() xor 1) }
        assertFailsWith<VoucherCryptoException> {
            crypto(kekVersion = "k1").decryptAndVerify(
                IDENTITY,
                encrypted.copy(codeCiphertext = tamperedCiphertext),
                stableDigest,
            )
        }.reason shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG

        val wrongDigest = digests.stableDedup(IDENTITY.tenantId, CanonicalVoucherCode.of("OTHER"))
        assertFailsWith<VoucherCryptoException> {
            crypto(kekVersion = "k1").decryptAndVerify(IDENTITY, encrypted, wrongDigest)
        }.reason shouldBeEqualTo VoucherCryptoFailureReason.DIGEST_MISMATCH

        assertFailsWith<VoucherCryptoException> {
            crypto(kekVersion = "k1").decryptAndVerify(
                IDENTITY.copy(entryId = UUID.randomUUID()),
                encrypted,
                stableDigest,
            )
        }.reason shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG
    }

    @Test
    fun `retained KEK decrypts old rows while new rows use the current version`() {
        val code = CanonicalVoucherCode.of("POOL-ROTATION")
        val oldCrypto = crypto(kekVersion = "k1")
        val oldEncrypted = oldCrypto.encrypt(IDENTITY, code)
        val rotated = crypto(kekVersion = "k2", retainedKekVersion = "k1")

        rotated.decryptAndVerify(
            IDENTITY,
            oldEncrypted,
            digests.stableDedup(IDENTITY.tenantId, code),
        ) shouldBeEqualTo code
        rotated.encrypt(IDENTITY, code).kekVersion shouldBeEqualTo "k2"
    }

    private fun crypto(
        kekVersion: String,
        retainedKekVersion: String? = null,
    ): VoucherEnvelopeCrypto {
        val retained = retainedKekVersion?.let { listOf(VoucherKek.of(it, kekBytes(it))) }.orEmpty()
        return AesGcmVoucherEnvelopeCrypto(
            VoucherKekRing.of(VoucherKek.of(kekVersion, kekBytes(kekVersion)), retained),
            digests,
        )
    }

    private fun kekBytes(version: String): ByteArray = ByteArray(32) { (version.hashCode() + it).toByte() }

    private val digests = VoucherDigestService.testFixture()

    companion object {
        private val IDENTITY = EntryIdentity(
            tenantId = "tenant-a",
            campaignId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            batchId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            entryId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            sourceOrdinal = 17,
        )
    }
}

private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
