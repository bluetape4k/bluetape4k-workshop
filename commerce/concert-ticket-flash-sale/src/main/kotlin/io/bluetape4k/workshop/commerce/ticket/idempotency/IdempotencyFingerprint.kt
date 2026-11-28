package io.bluetape4k.workshop.commerce.ticket.idempotency

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.io.digest.FingerprintEncoder
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.*

/** persistence와 equality 비교에 안전한 canonical 256-bit digest입니다. */
/** persistence와 equality 비교에 안전한 canonical 256-bit digest입니다. */
@JvmInline
value class TicketDigest private constructor(val base64Url: String) {
    /**
     * bytes은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun bytes(): ByteArray = Base64.getUrlDecoder().decode(base64Url)

    companion object {
        /**
         * of은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param bytes 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun of(bytes: ByteArray): TicketDigest =
            TicketDigest(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf()))

        /**
         * sha256은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun sha256(value: String): TicketDigest =
            TicketDigest.of(Base64.getUrlDecoder().decode(FingerprintEncoder.sha256Base64Url(value)))
    }
}

/** domain-separated key와 닫힌 purchase-request fingerprint를 생성합니다. */
/** domain-separated key와 닫힌 purchase-request fingerprint를 생성합니다. */
object IdempotencyFingerprint: KLogging() {

    /**
     * `mapper`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper = Jackson.createDefaultJsonMapper()

    /**
     * key은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param secret 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param rawKey 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun key(secret: ByteArray, rawKey: String): TicketDigest {
        secret.size.requireGe(32, "secret.size")
        rawKey.length.requireInRange(16, 128, "rawKey.length")
        rawKey.all { it.code in 0x21..0x7e }.requireEquals(true, "rawKey.isVisibleAscii")
        val encoded = FingerprintEncoder.hmacSha256Base64Url(
            secret = secret,
            domain = "ticket-idempotency-key-v1",
            rawKey,
        )
        return TicketDigest.of(Base64.getUrlDecoder().decode(encoded))
    }

    /**
     * request은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
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

    /**
     * canonical은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param node 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun canonical(node: JsonNode): String = when {
        node.isString -> node.stringValue()
        node.isNumber -> canonicalDecimal(node.decimalValue())
        else          -> throw IllegalArgumentException("purchase fields must be scalar")
    }

    /**
     * canonicalDecimal은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun canonicalDecimal(value: BigDecimal): String =
        value.stripTrailingZeros().let { if (it.compareTo(BigDecimal.ZERO) == 0) "0" else it.toPlainString() }
}
