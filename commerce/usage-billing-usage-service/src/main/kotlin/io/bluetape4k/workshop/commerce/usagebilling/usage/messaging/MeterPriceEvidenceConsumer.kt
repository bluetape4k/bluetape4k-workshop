package io.bluetape4k.workshop.commerce.usagebilling.usage.messaging

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.shared.integration.PayloadDigest
import io.bluetape4k.workshop.commerce.usagebilling.usage.application.PriceEvidenceService
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/** shared runtime message dependency를 도입하지 않고 Meter가 publish한 JSON을 decode합니다. */
/** shared runtime message dependency를 도입하지 않고 Meter가 publish한 JSON을 decode합니다. */
@Component
class MeterPriceEvidenceDecoder {

    private companion object: KLogging() {
        /**
         * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
         */
        const val PRICE_ACTIVATED = "PriceActivated"
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
    fun decode(wirePayload: String): PriceEvidenceInboxEvent {
        val envelope = Jackson.defaultJsonMapper.readTree(wirePayload)
        val eventId = UUID.fromString(envelope.requiredText("eventId"))
        val eventType = envelope.requiredText("eventType")
        val schemaVersion = envelope.requiredInt("schemaVersion")

        if (eventType != PRICE_ACTIVATED || schemaVersion != SCHEMA_VERSION) {
            throw UnsupportedMeterPriceEvidenceEnvelope(eventType, schemaVersion)
        }

        val tenantId = envelope.requiredText("tenantId")
        val aggregateVersion = envelope.requiredLong("aggregateVersion")
        aggregateVersion.requirePositiveNumber("aggregateVersion")
        val payload = envelope.requiredText("payload")
        val payloadDigest = envelope.requiredText("payloadDigest")

        if (!PayloadDigest.matches(payload, payloadDigest)) {
            throw InvalidMeterPriceEvidenceEnvelope(eventId)
        }

        val price = Jackson.defaultJsonMapper.readTree(payload)
        return PriceEvidenceInboxEvent(
            eventId = eventId,
            tenantId = tenantId,
            payloadDigest = payloadDigest,
            evidence = PriceEvidence(
                tenantId = tenantId,
                meterCode = price.requiredText("meterCode"),
                currency = price.requiredText("currency"),
                unitPrice = BigDecimal(price.requiredText("unitPrice")),
                effectiveAt = Instant.parse(envelope.requiredText("occurredAt")),
            ),
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
        requiredNode(name).asString().requireNotBlank("meterEnvelope.$name")

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
        get(name) ?: throw InvalidMeterPriceEvidenceEnvelope(UUID(0, 0))
}

/**
 * UnsupportedMeterPriceEvidenceEnvelope은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UnsupportedMeterPriceEvidenceEnvelope(
    eventType: String,
    schemaVersion: Int,
): IllegalArgumentException("unsupported_meter_price_evidence_envelope:$eventType:$schemaVersion")

/**
 * InvalidMeterPriceEvidenceEnvelope은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class InvalidMeterPriceEvidenceEnvelope(eventId: UUID):
    IllegalArgumentException("invalid_meter_price_evidence_envelope:$eventId")
/**
 * KafkaUsagePriceEvidenceListener은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property decoder 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property priceEvidence 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 */


@Component
class KafkaUsagePriceEvidenceListener(
    /**
     * `decoder`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val decoder: MeterPriceEvidenceDecoder,
    /**
     * `priceEvidence`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val priceEvidence: PriceEvidenceService,
) {

    private companion object: KLogging() {
        /**
         * `METER_TOPIC`은 사용량 과금·정산에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
         *
         * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
         */
        const val METER_TOPIC = "meter.events.v1"
    }

    @KafkaListener(
        topics = [METER_TOPIC],
        containerFactory = "usageKafkaListenerContainerFactory",
        groupId = "\${usage-billing.usage.kafka.consumer-group:usage-billing-usage-service}",
        autoStartup = "\${usage-billing.usage.kafka.listener-auto-startup:false}",
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
        priceEvidence.record(event)
        log.debug { "usage.inbound.price_activated eventId=${event.eventId}" }
    }
}
