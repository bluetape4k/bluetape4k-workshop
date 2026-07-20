@file:Suppress("TooManyFunctions") // Security value types and the transactional storage primitive share one boundary.

package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.LockedVoucherCryptoRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.sql.Connection
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class EntryIdentity(
    val tenantId: String,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val sourceOrdinal: Long,
) {
    init {
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(sourceOrdinal >= 0) { "sourceOrdinal must not be negative" }
    }
}

internal class VoucherKek private constructor(
    val version: String,
    private val material: ByteArray,
) {
    internal fun copyMaterial(): ByteArray = material.copyOf()

    override fun toString(): String = "VoucherKek(version=$version, material=[REDACTED])"

    companion object {
        fun of(version: String, material: ByteArray): VoucherKek {
            require(version.isNotBlank()) { "KEK version must not be blank" }
            require(material.size in VALID_AES_KEY_BYTES) { "KEK must be a valid AES key" }
            return VoucherKek(version, material.copyOf())
        }
    }
}

internal class VoucherKekRing private constructor(
    val current: VoucherKek,
    retained: List<VoucherKek>,
) {
    private val readable = (listOf(current) + retained).associateBy(VoucherKek::version)

    init {
        require(readable.size == retained.size + 1) { "KEK versions must be unique" }
    }

    fun require(version: String): VoucherKek = readable[version]
        ?: throw VoucherCryptoException(VoucherCryptoFailureReason.UNKNOWN_KEY_VERSION)

    companion object {
        fun of(current: VoucherKek, retained: List<VoucherKek> = emptyList()): VoucherKekRing =
            VoucherKekRing(current, retained.toList())
    }
}

internal class EncryptedVoucherCode private constructor(
    codeCiphertext: ByteArray,
    codeNonce: ByteArray,
    wrappedDek: ByteArray,
    wrapNonce: ByteArray,
    val kekVersion: String,
) {
    private val codeCiphertext = codeCiphertext.copyOf()
    private val codeNonce = codeNonce.copyOf()
    private val wrappedDek = wrappedDek.copyOf()
    private val wrapNonce = wrapNonce.copyOf()

    init {
        require(this.codeCiphertext.isNotEmpty()) { "code ciphertext must not be empty" }
        require(this.codeNonce.size == GCM_NONCE_BYTES) { "code nonce must be 96 bits" }
        require(this.wrappedDek.isNotEmpty()) { "wrapped DEK must not be empty" }
        require(this.wrapNonce.size == GCM_NONCE_BYTES) { "wrap nonce must be 96 bits" }
        require(kekVersion.isNotBlank()) { "KEK version must not be blank" }
    }

    fun copyCodeCiphertext(): ByteArray = codeCiphertext.copyOf()
    fun copyCodeNonce(): ByteArray = codeNonce.copyOf()
    fun copyWrappedDek(): ByteArray = wrappedDek.copyOf()
    fun copyWrapNonce(): ByteArray = wrapNonce.copyOf()

    fun copy(
        codeCiphertext: ByteArray = copyCodeCiphertext(),
        codeNonce: ByteArray = copyCodeNonce(),
        wrappedDek: ByteArray = copyWrappedDek(),
        wrapNonce: ByteArray = copyWrapNonce(),
        kekVersion: String = this.kekVersion,
    ): EncryptedVoucherCode = of(codeCiphertext, codeNonce, wrappedDek, wrapNonce, kekVersion)

    override fun toString(): String = "EncryptedVoucherCode(kekVersion=$kekVersion, material=[REDACTED])"

    companion object {
        fun of(
            codeCiphertext: ByteArray,
            codeNonce: ByteArray,
            wrappedDek: ByteArray,
            wrapNonce: ByteArray,
            kekVersion: String,
        ): EncryptedVoucherCode = EncryptedVoucherCode(codeCiphertext, codeNonce, wrappedDek, wrapNonce, kekVersion)
    }
}

internal enum class VoucherCryptoFailureReason {
    UNKNOWN_KEY_VERSION,
    INVALID_CIPHERTEXT,
    INVALID_TAG,
    DIGEST_MISMATCH,
}

internal class VoucherCryptoException(
    val reason: VoucherCryptoFailureReason,
    cause: Throwable? = null,
) : IllegalStateException(reason.name, cause)

internal interface VoucherEnvelopeCrypto {
    fun encrypt(entryIdentity: EntryIdentity, code: CanonicalVoucherCode): EncryptedVoucherCode

    fun decryptAndVerify(
        entryIdentity: EntryIdentity,
        encrypted: EncryptedVoucherCode,
        expectedStableDedup: VoucherDigest,
    ): CanonicalVoucherCode
}

/** JDK AES-GCM envelope encryption with a random per-entry DEK and versioned KEK wrapping. */
internal class AesGcmVoucherEnvelopeCrypto(
    private val kekRing: VoucherKekRing,
    private val digests: VoucherDigestService,
    private val secureRandom: SecureRandom = SecureRandom(),
) : VoucherEnvelopeCrypto {
    override fun encrypt(entryIdentity: EntryIdentity, code: CanonicalVoucherCode): EncryptedVoucherCode {
        val dek = randomBytes(DEK_BYTES)
        val codeNonce = randomBytes(GCM_NONCE_BYTES)
        val wrapNonce = randomBytes(GCM_NONCE_BYTES)
        val plaintext = code.withRawValue { it.toByteArray(StandardCharsets.UTF_8) }
        val kek = kekRing.current
        return try {
            EncryptedVoucherCode.of(
                codeCiphertext = encrypt(dek, codeNonce, codeAad(entryIdentity), plaintext),
                codeNonce = codeNonce,
                wrappedDek = withKeyMaterial(kek) { encrypt(it, wrapNonce, wrapAad(entryIdentity, kek.version), dek) },
                wrapNonce = wrapNonce,
                kekVersion = kek.version,
            )
        } catch (failure: GeneralSecurityException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT, failure)
        } finally {
            dek.fill(0)
            plaintext.fill(0)
        }
    }

    override fun decryptAndVerify(
        entryIdentity: EntryIdentity,
        encrypted: EncryptedVoucherCode,
        expectedStableDedup: VoucherDigest,
    ): CanonicalVoucherCode {
        val kek = kekRing.require(encrypted.kekVersion)
        val dek = try {
            withKeyMaterial(kek) {
                decrypt(it, encrypted.copyWrapNonce(), wrapAad(entryIdentity, kek.version), encrypted.copyWrappedDek())
            }
        } catch (failure: AEADBadTagException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_TAG, failure)
        } catch (failure: GeneralSecurityException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT, failure)
        }
        val plaintext = try {
            decrypt(dek, encrypted.copyCodeNonce(), codeAad(entryIdentity), encrypted.copyCodeCiphertext())
        } catch (failure: AEADBadTagException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_TAG, failure)
        } catch (failure: GeneralSecurityException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT, failure)
        } finally {
            dek.fill(0)
        }
        return try {
            val code = CanonicalVoucherCode.of(decodeUtf8(plaintext))
            val matches = try {
                digests.matchesStableDedup(entryIdentity.tenantId, code, expectedStableDedup)
            } catch (failure: VoucherKeyMaterialUnavailableException) {
                throw VoucherCryptoException(VoucherCryptoFailureReason.UNKNOWN_KEY_VERSION, failure)
            }
            if (!matches) throw VoucherCryptoException(VoucherCryptoFailureReason.DIGEST_MISMATCH)
            code
        } catch (failure: VoucherCryptoException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT, failure)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    private fun codeAad(identity: EntryIdentity): ByteArray = CanonicalFields.encode(
        "voucher-code-aad-v1",
        identity.tenantId.toByteArray(StandardCharsets.UTF_8),
        identity.campaignId.bytes(),
        identity.batchId.bytes(),
        identity.entryId.bytes(),
        ByteBuffer.allocate(Long.SIZE_BYTES).putLong(identity.sourceOrdinal).array(),
    )

    private fun wrapAad(identity: EntryIdentity, kekVersion: String): ByteArray = CanonicalFields.encode(
        "voucher-dek-wrap-aad-v1",
        identity.tenantId.toByteArray(StandardCharsets.UTF_8),
        identity.campaignId.bytes(),
        identity.batchId.bytes(),
        identity.entryId.bytes(),
        ByteBuffer.allocate(Long.SIZE_BYTES).putLong(identity.sourceOrdinal).array(),
        kekVersion.toByteArray(StandardCharsets.UTF_8),
    )

    private fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    private fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance(AES_GCM).apply {
            init(mode, SecretKeySpec(key, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
        }

    private fun <T> withKeyMaterial(key: VoucherKek, block: (ByteArray) -> T): T {
        val material = key.copyMaterial()
        return try {
            block(material)
        } finally {
            material.fill(0)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

/** Entry-lock crypto primitive composed inside the caller-owned reveal transaction. */
internal class VoucherCryptoStorage(
    private val repository: VoucherPoolRepository,
    private val crypto: VoucherEnvelopeCrypto,
) {
    // Persist only the bounded quarantine reason; never expose malformed crypto material.
    @Suppress("SwallowedException")
    fun decryptAndErase(
        connection: Connection,
        identity: EntryIdentity,
        expectedRevision: Long,
    ): VoucherCryptoStorageOutcome {
        check(!connection.autoCommit) { "voucher crypto storage requires a caller-owned transaction" }
        val locked = repository.lockAllocatedCryptoEntry(
            connection,
            identity.tenantId,
            identity.campaignId,
            identity.batchId,
            identity.entryId,
            identity.sourceOrdinal,
            expectedRevision,
        )
            ?: error("encrypted voucher entry is missing, stale, revealed, or already quarantined")
        return try {
            val code = crypto.decryptAndVerify(identity, locked.encrypted(), locked.stableDedup())
            repository.eraseVoucherCiphertext(connection, identity.tenantId, identity.entryId, expectedRevision)
            VoucherCryptoStorageOutcome.Revealed(code)
        } catch (failure: VoucherCryptoException) {
            quarantine(connection, identity, locked, failure.reason)
            VoucherCryptoStorageOutcome.Quarantined(failure.reason)
        } catch (failure: IllegalArgumentException) {
            quarantine(connection, identity, locked, VoucherCryptoFailureReason.INVALID_CIPHERTEXT)
            VoucherCryptoStorageOutcome.Quarantined(VoucherCryptoFailureReason.INVALID_CIPHERTEXT)
        }
    }

    private fun quarantine(
        connection: Connection,
        identity: EntryIdentity,
        locked: LockedVoucherCryptoRecord,
        reason: VoucherCryptoFailureReason,
    ) = repository.quarantineVoucherCrypto(
        connection,
        identity.tenantId,
        identity.entryId,
        locked.state,
        locked.revision,
        reason.name,
    )

    private fun LockedVoucherCryptoRecord.stableDedup(): VoucherDigest = VoucherDigest.of(
        DigestPurpose.STABLE_DEDUP,
        stableDedupKeyVersion,
        stableDedupDigest.requireCryptoBytes(),
    )

    private fun LockedVoucherCryptoRecord.encrypted(): EncryptedVoucherCode = EncryptedVoucherCode.of(
        codeCiphertext.requireCryptoBytes(),
        codeNonce.requireCryptoBytes(),
        wrappedDek.requireCryptoBytes(),
        wrapNonce.requireCryptoBytes(),
        kekVersion ?: throw IllegalArgumentException("missing KEK version"),
    )
}

internal sealed interface VoucherCryptoStorageOutcome {
    data class Revealed(val code: CanonicalVoucherCode) : VoucherCryptoStorageOutcome
    data class Quarantined(val reason: VoucherCryptoFailureReason) : VoucherCryptoStorageOutcome
}

private fun DigestValue?.requireCryptoBytes(): ByteArray =
    this?.copyBytes() ?: throw IllegalArgumentException("missing crypto material")

private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES * 2)
    .putLong(mostSignificantBits)
    .putLong(leastSignificantBits)
    .array()

private const val AES = "AES"
private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val GCM_NONCE_BYTES = 12
private const val DEK_BYTES = 32
private const val AES_128_BYTES = 16
private const val AES_192_BYTES = 24
private const val AES_256_BYTES = 32
private val VALID_AES_KEY_BYTES = setOf(AES_128_BYTES, AES_192_BYTES, AES_256_BYTES)
