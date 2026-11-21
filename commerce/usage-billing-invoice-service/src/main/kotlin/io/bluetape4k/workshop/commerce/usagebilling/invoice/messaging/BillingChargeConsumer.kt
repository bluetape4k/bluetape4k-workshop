package io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.shared.integration.PayloadDigest
import io.bluetape4k.workshop.commerce.usagebilling.invoice.application.InvoiceInboxService
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.*

/** producer type을 공유하지 않고 Billing의 wire contract를 Invoice-owned inbox data로 매핑합니다. */
/** producer type을 공유하지 않고 Billing의 wire contract를 Invoice-owned inbox data로 매핑합니다. */
@Component
class BillingChargeDecoder {

    private companion object: KLogging() {
        /**
         * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val SUPPORTED_EVENT_TYPES = setOf("ChargeRated", "AdjustmentPosted")
        /**
         * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
         */
        const val SCHEMA_VERSION = 1
    }

    /**
     * decode은 사용량 계량·청구 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param wirePayload 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun decode(wirePayload: String): InvoiceInboxEvent {
        val envelope = Jackson.defaultJsonMapper.readTree(wirePayload)
        val eventType = envelope.requiredText("eventType")
        val schemaVersion = envelope.requiredInt("schemaVersion")
        if (eventType !in SUPPORTED_EVENT_TYPES || schemaVersion != SCHEMA_VERSION) {
            throw UnsupportedBillingChargeEnvelope(eventType, schemaVersion)
        }
        val payload = envelope.requiredText("payload")
        val digest = envelope.requiredText("payloadDigest")
        val eventId = UUID.fromString(envelope.requiredText("eventId"))
        if (!PayloadDigest.matches(payload, digest)) throw InvalidBillingChargeEnvelope(eventId)
        val body = Jackson.defaultJsonMapper.readTree(payload)

        return InvoiceInboxEvent(
            eventId = eventId,
            eventType = eventType,
            correctionOf = body.optionalUuid("correctionOf"),
            amount = BigDecimal(body.requiredText("amount")),
            tenantId = envelope.requiredText("tenantId"),
            payloadDigest = digest,
        )
    }

    /**
     * requiredText은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.requiredText(name: String): String =
        requiredNode(name).asString().requireNotBlank("billingChargeEnvelope.$name")

    /**
     * requiredInt은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.requiredInt(name: String): Int =
        requiredNode(name).asInt()

    /**
     * requiredNode은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.requiredNode(name: String): JsonNode =
        get(name) ?: throw InvalidBillingChargeEnvelope(UUID(0, 0))

    /**
     * optionalUuid은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.optionalUuid(name: String): UUID? =
        get(name)?.asString()?.takeIf(String::isNotBlank)?.let(UUID::fromString)
}

/**
 * UnsupportedBillingChargeEnvelope은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UnsupportedBillingChargeEnvelope(eventType: String, schemaVersion: Int):
    IllegalArgumentException("unsupported_invoice_billing_envelope:$eventType:$schemaVersion")

/**
 * InvalidBillingChargeEnvelope은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class InvalidBillingChargeEnvelope(eventId: UUID):
    IllegalArgumentException("invalid_invoice_billing_envelope:$eventId")
/**
 * KafkaBillingChargeListener은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property decoder 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property inbox 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class KafkaBillingChargeListener(
    /**
     * `decoder`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val decoder: BillingChargeDecoder,
    /**
     * `inbox`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val inbox: InvoiceInboxService,
) {
    private companion object: KLogging() {
        /**
         * `TOPIC`은 사용량 과금·정산에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
         *
         * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
         */
        const val TOPIC = "billing.events.v1"
    }
    
    @KafkaListener(
        topics = [TOPIC],
        containerFactory = "invoiceKafkaListenerContainerFactory",
        groupId = "\${usage-billing.invoice.kafka.consumer-group:usage-billing-invoice-service}",
        autoStartup = "\${usage-billing.invoice.kafka.listener-auto-startup:false}",
    )
    /**
     * consume은 사용량 계량·청구의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param wirePayload 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun consume(wirePayload: String) {
        val event = decoder.decode(wirePayload)
        inbox.handle(event)
        log.debug { "invoice.inbound.billing_charge eventId=${event.eventId} eventType=${event.eventType}" }
    }
}
