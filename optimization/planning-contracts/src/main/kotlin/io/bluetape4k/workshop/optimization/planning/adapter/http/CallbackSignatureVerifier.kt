package io.bluetape4k.workshop.optimization.planning.adapter.http

import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun interface CallbackSignatureVerifier {
    fun verify(provider: PlanningProvider, rawBody: ByteArray, signature: String?): Boolean
}

internal class FakeCallbackSignatureVerifier: CallbackSignatureVerifier {
    override fun verify(provider: PlanningProvider, rawBody: ByteArray, signature: String?): Boolean =
        provider == PlanningProvider.FAKE && signature == "fake"
}

internal class HmacSha256CallbackSignatureVerifier(
    secret: String,
): CallbackSignatureVerifier {
    private val secretKey = SecretKeySpec(
        secret.toByteArray(StandardCharsets.UTF_8),
        HMAC_SHA_256,
    )

    init {
        require(secret.isNotBlank()) { "callback secret must not be blank" }
    }

    override fun verify(provider: PlanningProvider, rawBody: ByteArray, signature: String?): Boolean {
        val supplied = signature
            ?.removePrefix("sha256=")
            ?.takeIf { it.length == SHA_256_HEX_LENGTH }
            ?.hexToByteArrayOrNull()
            ?: return false
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(secretKey)
        return MessageDigest.isEqual(mac.doFinal(rawBody), supplied)
    }

    private fun String.hexToByteArrayOrNull(): ByteArray? = runCatching {
        ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    companion object {
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val SHA_256_HEX_LENGTH = 64
    }
}
