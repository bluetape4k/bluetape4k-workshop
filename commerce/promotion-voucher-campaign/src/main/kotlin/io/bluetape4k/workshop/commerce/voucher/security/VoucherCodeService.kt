package io.bluetape4k.workshop.commerce.voucher.security

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class VoucherCodeKeyRing(
    val currentGenerationVersion: Int,
    val currentVerificationVersion: Int,
    val generationKeys: Map<Int, ByteArray>,
    val verificationKeys: Map<Int, ByteArray>,
) {
    init {
        require(currentGenerationVersion in MIN_KEY_VERSION..MAX_KEY_VERSION)
        require(currentVerificationVersion in MIN_KEY_VERSION..MAX_KEY_VERSION)
        require(generationKeys[currentGenerationVersion] != null) { "current generation key is missing" }
        require(verificationKeys[currentVerificationVersion] != null) { "current verification key is missing" }
    }

    companion object {
        private const val MIN_KEY_VERSION = 1
        private const val MAX_KEY_VERSION = 99
    }
}

internal data class VoucherGenerationInput(
    val tenantId: String,
    val campaignId: UUID,
    val allocationId: UUID,
) {
    init {
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
    }

    fun canonicalBytes(): ByteArray =
        "$tenantId\u0000$campaignId\u0000$allocationId".toByteArray(StandardCharsets.UTF_8)
}

internal data class IssuedVoucherCode(
    val code: String,
    val verifier: ByteArray,
    val generationKeyVersion: Int,
    val verificationKeyVersion: Int,
)

internal data class VoucherLookupMaterial(
    val verifier: ByteArray,
    val verificationKeyVersion: Int,
)

internal enum class VerificationResult {
    VALID,
    INVALID_CODE,
}

/** Generates replayable opaque codes while persisting only a domain-separated verifier. */
internal class VoucherCodeService(
    private val keyRing: VoucherCodeKeyRing,
) {
    fun issue(input: VoucherGenerationInput): IssuedVoucherCode =
        checkNotNull(
            reconstruct(
                input,
                generationKeyVersion = keyRing.currentGenerationVersion,
                verificationKeyVersion = keyRing.currentVerificationVersion,
            ),
        ) { "current voucher keys disappeared after key-ring validation" }

    /** Reconstructs a committed code only while both recorded key versions remain available. */
    fun reconstruct(
        input: VoucherGenerationInput,
        generationKeyVersion: Int,
        verificationKeyVersion: Int,
    ): IssuedVoucherCode? {
        val generationKey = keyRing.generationKeys[generationKeyVersion] ?: return null
        val verificationKey = keyRing.verificationKeys[verificationKeyVersion] ?: return null
        val tokenMaterial = hmac(generationKey, GENERATION_DOMAIN, input.canonicalBytes()).copyOf(TOKEN_BYTES)
        val payload = Base58.encode(tokenMaterial).padStart(PAYLOAD_LENGTH, BASE58_ZERO)
        check(payload.length == PAYLOAD_LENGTH) { "voucher token material exceeded the bounded payload" }

        val prefix = "V$verificationKeyVersion-$payload"
        val code = prefix + checksum(prefix)
        val verifier = verifier(code, verificationKey)
        return IssuedVoucherCode(
            code = code,
            verifier = verifier,
            generationKeyVersion = generationKeyVersion,
            verificationKeyVersion = verificationKeyVersion,
        )
    }

    /** Converts a canonical external code into the opaque tenant-scoped lookup material. */
    fun lookup(candidate: String): VoucherLookupMaterial? {
        val parsed = parse(candidate)
        val key = parsed?.let { keyRing.verificationKeys[it.verificationKeyVersion] } ?: DUMMY_KEY
        val digest = verifier(candidate.take(MAX_CODE_LENGTH), key)
        return if (parsed != null && keyRing.verificationKeys.containsKey(parsed.verificationKeyVersion)) {
            VoucherLookupMaterial(digest, parsed.verificationKeyVersion)
        } else {
            log.debug { "voucher_code_rejected reason=INVALID_CODE" }
            null
        }
    }

    fun verify(
        candidate: String,
        expectedVerifier: ByteArray,
        verificationKeyVersion: Int,
    ): Boolean {
        val parsed = parse(candidate)
        val key = keyRing.verificationKeys[verificationKeyVersion] ?: DUMMY_KEY
        val actualVerifier = verifier(candidate.take(MAX_CODE_LENGTH), key)
        val matched = MessageDigest.isEqual(actualVerifier, expectedVerifier)
        val valid =
            parsed != null &&
                parsed.verificationKeyVersion == verificationKeyVersion &&
                keyRing.verificationKeys.containsKey(verificationKeyVersion) &&
                matched
        if (!valid) log.debug { "voucher_code_rejected reason=INVALID_CODE" }
        return valid
    }

    fun verifyExternal(candidate: String): VerificationResult {
        val parsed = parse(candidate)
        val key = parsed?.let { keyRing.verificationKeys[it.verificationKeyVersion] } ?: DUMMY_KEY
        verifier(candidate.take(MAX_CODE_LENGTH), key)
        return if (parsed != null && keyRing.verificationKeys.containsKey(parsed.verificationKeyVersion)) {
            VerificationResult.VALID
        } else {
            log.debug { "voucher_code_rejected reason=INVALID_CODE" }
            VerificationResult.INVALID_CODE
        }
    }

    private fun parse(candidate: String): ParsedVoucherCode? {
        if (candidate.length !in MIN_CODE_LENGTH..MAX_CODE_LENGTH || !candidate.all { it.code in ASCII_RANGE }) return null
        val match = CODE_PATTERN.matchEntire(candidate) ?: return null
        val verificationKeyVersion = match.groupValues[1].toIntOrNull() ?: return null
        val payload = match.groupValues[2]
        val suppliedChecksum = match.groupValues[3]
        val prefix = "V$verificationKeyVersion-$payload"
        if (!MessageDigest.isEqual(checksum(prefix).toByteArray(), suppliedChecksum.toByteArray())) return null
        return ParsedVoucherCode(verificationKeyVersion)
    }

    private fun verifier(
        code: String,
        key: ByteArray,
    ): ByteArray = hmac(key, VERIFIER_DOMAIN, code.toByteArray(StandardCharsets.US_ASCII))

    private fun checksum(prefix: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(prefix.toByteArray(StandardCharsets.US_ASCII))
        val checksumValue =
            (((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)) %
                (BASE58_ALPHABET.length * BASE58_ALPHABET.length)
        return buildString(CHECKSUM_LENGTH) {
            append(BASE58_ALPHABET[checksumValue / BASE58_ALPHABET.length])
            append(BASE58_ALPHABET[checksumValue % BASE58_ALPHABET.length])
        }
    }

    private fun hmac(
        key: ByteArray,
        domain: String,
        input: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(key, HMAC_SHA_256))
        mac.update(domain.toByteArray(StandardCharsets.US_ASCII))
        mac.update(DOMAIN_SEPARATOR)
        return mac.doFinal(input)
    }

    private data class ParsedVoucherCode(val verificationKeyVersion: Int)

    companion object : KLogging() {
        private const val GENERATION_DOMAIN = "voucher-generation"
        private const val VERIFIER_DOMAIN = "voucher-verifier"
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val SHA_256 = "SHA-256"
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private const val BASE58_ZERO = '1'
        private const val TOKEN_BYTES = 16
        private const val PAYLOAD_LENGTH = 22
        private const val CHECKSUM_LENGTH = 2
        private const val MIN_CODE_LENGTH = 27
        private const val MAX_CODE_LENGTH = 28
        private val ASCII_RANGE = 0..127
        private val DOMAIN_SEPARATOR = byteArrayOf(0)
        private val DUMMY_KEY = ByteArray(32) { 0x5a }
        private val CODE_PATTERN = Regex("V([1-9][0-9]?)-([$BASE58_ALPHABET]{$PAYLOAD_LENGTH})([$BASE58_ALPHABET]{$CHECKSUM_LENGTH})")
    }
}
