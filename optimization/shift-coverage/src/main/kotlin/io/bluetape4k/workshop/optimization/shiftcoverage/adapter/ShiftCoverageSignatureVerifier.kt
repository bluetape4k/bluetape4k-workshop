package io.bluetape4k.workshop.optimization.shiftcoverage.adapter

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AggregateId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ProviderName
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** callback HMAC에 포함되는 versioned binding context입니다. */
data class ShiftCoverageSignatureContext(
    val method: String,
    val path: String,
    val schemaVersion: String,
    val provider: ProviderName,
    val requestId: String,
    val datasetId: String,
    val generationId: GenerationId,
    val aggregateId: PlanId,
    val siteId: SiteId,
    val eventId: EventId,
    val issuedAt: Instant,
) {
    init {
        if (listOf(method, path, schemaVersion, requestId, datasetId).any { it.isBlank() }) {
            throw InvalidShiftCoverageInput("signature context contains a blank field")
        }
    }

    fun withAggregateId(value: AggregateId): ShiftCoverageSignatureContext = copy(aggregateId = PlanId(value.value))
}

/** canonical bytes와 length-prefixed context를 constant-time으로 검증합니다. */
class ShiftCoverageSignatureVerifier(
    secret: String,
    private val expectedKeyVersion: String = "fixture-v1",
    private val maxAge: Duration = Duration.ofMinutes(5),
) {
    private val secretKey = SecretKeySpec(secret.toByteArray(UTF_8), HMAC_SHA_256)

    init {
        if (secret.isBlank()) throw InvalidShiftCoverageInput("signature secret must not be blank")
        if (expectedKeyVersion.isBlank() || maxAge.isNegative) throw InvalidShiftCoverageInput("invalid signature verifier configuration")
    }

    fun sign(canonicalBody: ByteArray, context: ShiftCoverageSignatureContext, keyVersion: String = expectedKeyVersion): String =
        "hmac-sha256=" + mac(canonicalBody, context, keyVersion).toHex()

    fun verify(
        canonicalBody: ByteArray,
        context: ShiftCoverageSignatureContext,
        signature: String?,
        keyVersion: String?,
        now: Instant,
    ): Boolean {
        if (keyVersion != expectedKeyVersion || signature.isNullOrBlank()) return false
        val age = Duration.between(context.issuedAt, now).abs()
        if (age > maxAge) return false
        val supplied = signature.removePrefix("hmac-sha256=").takeIf { it.length == 64 }?.hexOrNull() ?: return false
        return MessageDigest.isEqual(mac(canonicalBody, context, keyVersion), supplied)
    }

    private fun mac(body: ByteArray, context: ShiftCoverageSignatureContext, keyVersion: String): ByteArray {
        val encoded = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                val parts = listOf(
                    "v1", context.method, context.path, context.schemaVersion, context.provider.value,
                    context.requestId, context.datasetId, context.generationId.value, context.aggregateId.value,
                    context.siteId.value, context.eventId.value, context.issuedAt.toString(), keyVersion,
                )
                parts.forEach { value ->
                    val bytes = value.toByteArray(UTF_8)
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
                data.writeInt(body.size)
                data.write(body)
            }
            output.toByteArray()
        }
        val hmac = Mac.getInstance(HMAC_SHA_256)
        hmac.init(secretKey)
        return hmac.doFinal(encoded)
    }

    private fun String.hexOrNull(): ByteArray? = try {
        if (length % 2 != 0 || any { it !in "0123456789abcdefABCDEF" }) null
        else ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    } catch (_: NumberFormatException) {
        null
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object { private const val HMAC_SHA_256 = "HmacSHA256" }
}
