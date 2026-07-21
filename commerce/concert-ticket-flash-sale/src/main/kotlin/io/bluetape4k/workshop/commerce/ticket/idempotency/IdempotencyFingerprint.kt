package io.bluetape4k.workshop.commerce.ticket.idempotency

import io.bluetape4k.jackson3.jsonMapper
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Canonical 256-bit digest safe for persistence and equality. */
@JvmInline
value class TicketDigest private constructor(
    val base64Url: String,
) {
    fun bytes(): ByteArray = Base64.getUrlDecoder().decode(base64Url)

    companion object {
        fun of(bytes: ByteArray): TicketDigest =
            TicketDigest(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf()))

        fun sha256(value: String): TicketDigest =
            of(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))
    }
}

/** Builds domain-separated key and closed purchase-request fingerprints. */
object IdempotencyFingerprint {
    private val mapper = jsonMapper { }

    fun key(
        secret: ByteArray,
        rawKey: String,
    ): TicketDigest {
        require(secret.size >= 32) { "idempotency secret must contain at least 32 bytes" }
        require(rawKey.length in 16..128 && rawKey.all { it.code in 0x21..0x7e }) {
            "Idempotency-Key must contain 16..128 visible ASCII characters"
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.copyOf(), "HmacSHA256"))
        return TicketDigest.of(mac.doFinal("ticket-idempotency-key-v1\u0000$rawKey".toByteArray(UTF_8)))
    }

    fun request(
        method: String,
        path: String,
        body: String,
    ): TicketDigest {
        require(body.toByteArray(UTF_8).size <= 16 * 1024) { "request body is too large" }
        val root =
            try {
                mapper.readTree(body)
            } catch (failure: Exception) {
                throw IllegalArgumentException("request body must be valid JSON", failure)
            }
        require(root.isObject) { "request body must be a JSON object" }
        val names = root.propertyNames().asSequence().toSet()
        require(names == setOf("grade", "quantity")) { "purchase request must contain only grade and quantity" }
        val canonical =
            buildString {
                append("ticket-idempotency-request-v1\n")
                append(method.trim().uppercase(Locale.ROOT)).append('\n')
                append(path.trim()).append('\n')
                append("grade=").append(canonical(root.get("grade"))).append('\n')
                append("quantity=").append(canonical(root.get("quantity")))
            }
        return TicketDigest.sha256(canonical)
    }

    private fun canonical(node: JsonNode): String =
        when {
            node.isString -> node.stringValue()
            node.isNumber -> canonicalDecimal(node.decimalValue())
            else -> throw IllegalArgumentException("purchase fields must be scalar")
        }

    private fun canonicalDecimal(value: BigDecimal): String =
        value.stripTrailingZeros().let { if (it.compareTo(BigDecimal.ZERO) == 0) "0" else it.toPlainString() }
}
