package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Callback 서명은 canonical UTF-8 bytes에 대해서만 검증합니다. */
fun interface FieldServiceSignatureVerifier {
    fun verify(provider: FieldServiceProvider, canonicalBody: ByteArray, signature: String?): Boolean

    companion object {
        fun fixture(): FixtureFieldServiceSignatureVerifier = FixtureFieldServiceSignatureVerifier()
    }
}

/** 실제 provider credential을 포함하지 않는 deterministic fixture 서명기입니다. */
class FixtureFieldServiceSignatureVerifier(
    private val fixtureSecret: String = FIXTURE_SECRET,
) : FieldServiceSignatureVerifier {
    init {
        require(fixtureSecret.isNotBlank()) { "fixture secret must not be blank" }
    }

    override fun verify(
        provider: FieldServiceProvider,
        canonicalBody: ByteArray,
        signature: String?,
    ): Boolean {
        if (provider != FieldServiceProvider.FAKE) return false
        val supplied = signature
            ?.removePrefix("fixture-v1=")
            ?.takeIf { it.length == SHA_256_HEX_LENGTH }
            ?.hexToBytesOrNull()
            ?: return false
        return MessageDigest.isEqual(signBytes(provider, canonicalBody), supplied)
    }

    fun sign(provider: FieldServiceProvider, canonicalBody: ByteArray): String =
        "fixture-v1=" + signBytes(provider, canonicalBody).toHex()

    private fun signBytes(provider: FieldServiceProvider, canonicalBody: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(fixtureSecret.toByteArray(StandardCharsets.UTF_8), HMAC_SHA_256))
        return mac.doFinal((provider.name + ":").toByteArray(StandardCharsets.UTF_8) + canonicalBody)
    }

    companion object {
        const val FIXTURE_SECRET: String = "field-service-planning-fixture-v1"
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val SHA_256_HEX_LENGTH = 64
    }
}

/** configured provider secret용 HMAC verifier이며 secret material은 DTO에 들어가지 않습니다. */
class HmacSha256FieldServiceSignatureVerifier(
    secret: String,
) : FieldServiceSignatureVerifier {
    private val secretKey = SecretKeySpec(
        secret.toByteArray(StandardCharsets.UTF_8),
        HMAC_SHA_256,
    )

    init {
        require(secret.isNotBlank()) { "callback secret must not be blank" }
    }

    override fun verify(
        provider: FieldServiceProvider,
        canonicalBody: ByteArray,
        signature: String?,
    ): Boolean {
        val supplied = signature
            ?.removePrefix("sha256=")
            ?.takeIf { it.length == SHA_256_HEX_LENGTH }
            ?.hexToBytesOrNull()
            ?: return false
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(secretKey)
        return MessageDigest.isEqual(mac.doFinal(canonicalBody), supplied)
    }

    companion object {
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val SHA_256_HEX_LENGTH = 64
    }
}

private fun String.hexToBytesOrNull(): ByteArray? = runCatching {
    require(length % 2 == 0)
    ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}.getOrNull()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
