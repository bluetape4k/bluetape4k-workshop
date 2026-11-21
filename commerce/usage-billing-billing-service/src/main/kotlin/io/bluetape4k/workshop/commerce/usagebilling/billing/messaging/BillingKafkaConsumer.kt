package io.bluetape4k.workshop.commerce.usagebilling.billing.messaging

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.shared.integration.PayloadDigest
import io.bluetape4k.workshop.commerce.usagebilling.billing.application.BillingInboxService
import io.bluetape4k.workshop.commerce.usagebilling.billing.application.BillingPricingEvidenceService
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * BillingInboundEvent은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
sealed interface BillingInboundEvent: Serializable {
/**
 * PriceActivated은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property event 사용량 계량·청구 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 */


    data class PriceActivated(val event: BillingPriceEvidenceEvent): BillingInboundEvent {
        private companion object {
            /**
             * `serialVersionUID`은 사용량 과금·정산에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
/**
 * UsageAccepted은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property event 사용량 계량·청구 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 */


    data class UsageAccepted(val event: BillingInboxEvent): BillingInboundEvent {
        private companion object {
            /**
             * `serialVersionUID`은 사용량 과금·정산에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
}

/** Billing이 자체 compatibility decision을 소유하도록 producer JSON을 local에서 parse합니다. */
/** Billing이 자체 compatibility decision을 소유하도록 producer JSON을 local에서 parse합니다. */
@Component
class BillingInboundEventDecoder {

    private companion object {
        /**
         * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
         */
        const val PRICE_ACTIVATED = "PriceActivated"
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val USAGE_ACCEPTED = "UsageAccepted"
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
    fun decode(wirePayload: String): BillingInboundEvent {
        val envelope = Jackson.defaultJsonMapper.readTree(wirePayload)
        val eventType = envelope.requiredText("eventType")
        val schemaVersion = envelope.requiredInt("schemaVersion")

        requireSupportedSchema(eventType, schemaVersion)

        val eventId = UUID.fromString(envelope.requiredText("eventId"))
        val tenantId = envelope.requiredText("tenantId")
        val aggregateType = envelope.requiredText("aggregateType")
        val aggregateId = envelope.requiredText("aggregateId")
        val aggregateVersion = envelope.requiredLong("aggregateVersion")
        val payload = envelope.requiredText("payload")
        val payloadDigest = envelope.requiredText("payloadDigest")

        verifyPayloadDigest(eventId, payload, payloadDigest)

        val body = Jackson.defaultJsonMapper.readTree(payload)

        return when (eventType) {
            PRICE_ACTIVATED ->
                BillingInboundEvent.PriceActivated(
                    BillingPriceEvidenceEvent(
                        eventId = eventId,
                        payloadDigest = payloadDigest,
                        evidence = BillingPriceEvidence(
                            tenantId = tenantId,
                            meterCode = body.requiredText("meterCode"),
                            currency = body.requiredText("currency"),
                            unitPrice = BigDecimal(body.requiredText("unitPrice")),
                            effectiveAt = Instant.parse(envelope.requiredText("occurredAt")),
                        ),
                    ),
                )
            USAGE_ACCEPTED  ->
                BillingInboundEvent.UsageAccepted(
                    BillingInboxEvent(
                        eventId = eventId,
                        tenantId = tenantId,
                        aggregateType = aggregateType,
                        aggregateId = aggregateId,
                        aggregateVersion = aggregateVersion,
                        payloadDigest = payloadDigest,
                        meterCode = body.requiredText("meterCode"),
                        currency = body.requiredText("currency"),
                        quantity = BigDecimal(body.requiredText("quantity")),
                    ),
                )
            else            ->
                throw UnsupportedBillingInboundEnvelope(eventType, schemaVersion)
        }
    }

    /**
     * requireSupportedSchema은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분
     * 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param schemaVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun requireSupportedSchema(eventType: String, schemaVersion: Int) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw UnsupportedBillingInboundEnvelope(eventType, schemaVersion)
        }
    }

    /**
     * verifyPayloadDigest은 사용량 계량·청구 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param payload 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param payloadDigest 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun verifyPayloadDigest(eventId: UUID, payload: String, payloadDigest: String) {
        if (!PayloadDigest.matches(payload, payloadDigest)) {
            throw InvalidBillingInboundEnvelope(eventId)
        }
    }

    /**
     * requiredText은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.requiredText(name: String): String =
        requiredNode(name).asString().requireNotBlank("billingEnvelope.$name")

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
     * requiredLong은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.requiredLong(name: String): Long =
        requiredNode(name).asLong()

    /**
     * requiredNode은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.requiredNode(name: String): JsonNode =
        get(name) ?: throw InvalidBillingInboundEnvelope(UUID(0, 0))
}

/**
 * UnsupportedBillingInboundEnvelope은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UnsupportedBillingInboundEnvelope(eventType: String, schemaVersion: Int):
    IllegalArgumentException("unsupported_billing_inbound_envelope:$eventType:$schemaVersion")

/**
 * InvalidBillingInboundEnvelope은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class InvalidBillingInboundEnvelope(eventId: UUID):
    IllegalArgumentException("invalid_billing_inbound_envelope:$eventId")
/**
 * KafkaBillingIntegrationListener은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property decoder 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property pricingEvidence 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property inbox 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class KafkaBillingIntegrationListener(
    /**
     * `decoder`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val decoder: BillingInboundEventDecoder,
    /**
     * `pricingEvidence`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val pricingEvidence: BillingPricingEvidenceService,
    /**
     * `inbox`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val inbox: BillingInboxService,
) {
    private companion object: KLogging() {
        /**
         * `METER_TOPIC`은 사용량 과금·정산에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
         *
         * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
         */
        const val METER_TOPIC = "meter.events.v1"
        /**
         * `USAGE_TOPIC`은 사용량 과금·정산에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
         *
         * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
         */
        const val USAGE_TOPIC = "usage.events.v1"
    }

    @KafkaListener(
        topics = [METER_TOPIC, USAGE_TOPIC],
        containerFactory = "billingKafkaListenerContainerFactory",
        groupId = "\${usage-billing.billing.kafka.consumer-group:usage-billing-billing-service}",
        autoStartup = "\${usage-billing.billing.kafka.listener-auto-startup:false}",
    )
    /**
     * consume은 사용량 계량·청구의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param wirePayload 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun consume(wirePayload: String) {
        when (val event = decoder.decode(wirePayload)) {
            is BillingInboundEvent.PriceActivated -> {
                pricingEvidence.record(event.event)
                log.debug { "billing.inbound.price_activated eventId=${event.event.eventId}" }
            }
            is BillingInboundEvent.UsageAccepted -> {
                inbox.handle(event.event)
                log.debug { "billing.inbound.usage_accepted eventId=${event.event.eventId}" }
            }
        }
    }
}
