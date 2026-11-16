package io.bluetape4k.workshop.commerce.ticket.idempotency

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.toUtf8Bytes
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** persistence와 equality 비교에 안전한 canonical 256-bit digest입니다. */
@JvmInline
value class TicketDigest private constructor(val base64Url: String) {
    fun bytes(): ByteArray = Base64.getUrlDecoder().decode(base64Url)

    companion object {
        fun of(bytes: ByteArray): TicketDigest =
            TicketDigest(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf()))

        fun sha256(value: String): TicketDigest =
            of(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))
    }
}

/** domain-separated key와 닫힌 purchase-request fingerprint를 생성합니다. */
object IdempotencyFingerprint: KLogging() {

    private val mapper = Jackson.createDefaultJsonMapper()

    fun key(secret: ByteArray, rawKey: String): TicketDigest {
        secret.size.requireGe(32, "secret.size")
        rawKey.length.requireInRange(16, 128, "rawKey.length")
        rawKey.all { it.code in 0x21..0x7e }.requireEquals(true, "rawKey.isVisibleAscii")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.copyOf(), "HmacSHA256"))
        return TicketDigest.of(mac.doFinal("ticket-idempotency-key-v1\u0000$rawKey".toUtf8Bytes()))
    }

    fun request(
        method: String,
        path: String,
        body: String,
    ): TicketDigest {
        log.debug { "parse TicketDigest. method=$method, path=$path, body=$body" }
        body.toUtf8Bytes().size.requireInRange(0, 16 * 1024, "body.bytes")

        val root: JsonNode = try {
            mapper.readTree(body)
        } catch (failure: Exception) {
            throw IllegalArgumentException("request body must be valid JSON", failure)
        }

        require(root.isObject) { "body must be an object." }
        val names = root.propertyNames().toSet()
        require(names == setOf("grade", "quantity")) { "body.propertyNames must be set['grade', 'quantity']" }

        val canonical = buildString {
            appendLine("ticket-idempotency-request-v1")
            appendLine(method.trim().uppercase(Locale.ROOT))
            appendLine(path.trim())
            append("grade=").appendLine(canonical(root.get("grade")))
            append("quantity=").append(canonical(root.get("quantity")))
        }
        log.debug { "canonical=$canonical" }
        return TicketDigest.sha256(canonical)
    }

    private fun canonical(node: JsonNode): String = when {
        node.isString -> node.stringValue()
        node.isNumber -> canonicalDecimal(node.decimalValue())
        else          -> throw IllegalArgumentException("purchase fields must be scalar")
    }

    private fun canonicalDecimal(value: BigDecimal): String =
        value.stripTrailingZeros().let { if (it.compareTo(BigDecimal.ZERO) == 0) "0" else it.toPlainString() }
}
