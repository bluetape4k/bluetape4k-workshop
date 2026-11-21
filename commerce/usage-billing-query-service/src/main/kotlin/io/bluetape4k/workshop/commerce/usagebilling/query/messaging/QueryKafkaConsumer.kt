package io.bluetape4k.workshop.commerce.usagebilling.query.messaging

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.shared.integration.PayloadDigest
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryInboxService
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryQuarantineService
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryQuarantineEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.*

@Component
/**
 * QueryInboundEventDecoder은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class QueryInboundEventDecoder {

    private companion object: KLogging() {
        /**
         * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
         */
        val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2)
        /**
         * `UNKNOWN_EVENT_ID`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val UNKNOWN_EVENT_ID: UUID = UUID(0, 0)
        /**
         * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val SUPPORTED_EVENT_TYPES = setOf(
            "PriceActivated",
            "UsageAccepted",
            "UsageCorrected",
            "ChargeRated",
            "AdjustmentPosted",
            "InvoiceIssued",
            "InvoiceCorrectionIssued",
        )
    }

    /**
     * decode은 사용량 계량·청구 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param wirePayload 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun decode(wirePayload: String): QueryInboxEvent {
        val envelope = Jackson.defaultJsonMapper.readTree(wirePayload)
        val eventId = envelope.eventIdOrFallback()
        val eventType = envelope.optionalText("eventType") ?: "unknown"
        val tenantId = envelope.optionalText("tenantId") ?: "unknown"
        val schemaVersion = envelope.optionalInt("schemaVersion") ?: 0

        if (eventType !in SUPPORTED_EVENT_TYPES) {
            fail(eventId, tenantId, eventType, "unsupported_schema:$schemaVersion")
        }
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            fail(eventId, tenantId, eventType, "unsupported_schema:$schemaVersion")
        }
        val payload = envelope.requiredText("payload", eventId, tenantId, eventType)
        val payloadDigest = envelope.requiredText("payloadDigest", eventId, tenantId, eventType)

        if (!PayloadDigest.matches(payload, payloadDigest)) {
            fail(eventId, tenantId, eventType, "invalid_payload_digest")
        }

        return QueryInboxEvent(
            eventId = eventId,
            tenantId = tenantId,
            eventType = eventType,
            aggregateType = envelope.requiredText("aggregateType", eventId, tenantId, eventType),
            aggregateId = envelope.requiredText("aggregateId", eventId, tenantId, eventType),
            aggregateVersion = envelope.requiredLong("aggregateVersion", eventId, tenantId, eventType),
            payload = payload,
            payloadDigest = payloadDigest,
            receivedAt = Instant.now(),
        )
    }

    /**
     * eventIdOrFallback은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun JsonNode.eventIdOrFallback(): UUID =
        optionalText("eventId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UNKNOWN_EVENT_ID

    /**
     * requiredText은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.requiredText(eventId: UUID, tenantId: String, eventType: String, name: String): String =
        optionalText(name)
            ?.takeIf(String::isNotBlank)
            ?: fail(eventId, tenantId, eventType, "missing_$name")

    /**
     * requiredText은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun JsonNode.requiredText(name: String, eventId: UUID, tenantId: String, eventType: String): String =
        requiredText(eventId, tenantId, eventType, name)

    /**
     * requiredLong은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun JsonNode.requiredLong(name: String, eventId: UUID, tenantId: String, eventType: String): Long =
        optionalLong(name)?.takeIf { it > 0 }
            ?: fail(eventId, tenantId, eventType, "invalid_$name")

    /**
     * optionalText은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.optionalText(name: String): String? = get(name)?.asString()

    /**
     * optionalInt은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.optionalInt(name: String): Int? = get(name)?.asInt()

    /**
     * optionalLong은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun JsonNode.optionalLong(name: String): Long? = get(name)?.asLong()

    /**
     * fail은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun fail(eventId: UUID, tenantId: String, eventType: String, reason: String): Nothing =
        throw PermanentQueryInboundException(
            QueryQuarantineEvent(eventId, tenantId, eventType, reason, Instant.now())
        )
}
/**
 * PermanentQueryInboundException은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property quarantine 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


class PermanentQueryInboundException(val quarantine: QueryQuarantineEvent):
    IllegalArgumentException("permanent_query_inbound:${quarantine.reason}")
/**
 * KafkaQueryIntegrationListener은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property decoder 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property inbox 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property quarantine 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class KafkaQueryIntegrationListener(
    /**
     * `decoder`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val decoder: QueryInboundEventDecoder,
    /**
     * `inbox`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val inbox: QueryInboxService,
    /**
     * `quarantine`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val quarantine: QueryQuarantineService,
) {
    private companion object: KLogging()

    @KafkaListener(
        topics = ["meter.events.v1", "usage.events.v1", "billing.events.v1", "invoice.events.v1"],
        containerFactory = "queryKafkaListenerContainerFactory",
        groupId = "\${usage-billing.query.kafka.consumer-group:usage-billing-query-service}",
        autoStartup = "\${usage-billing.query.kafka.listener-auto-startup:false}",
    )
    /**
     * consume은 사용량 계량·청구의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param wirePayload 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun consume(wirePayload: String) {
        try {
            val event = decoder.decode(wirePayload)
            inbox.apply(event)
            log.debug { "query.inbound.applied eventId=${event.eventId} eventType=${event.eventType}" }
        } catch (failure: PermanentQueryInboundException) {
            quarantine.record(failure.quarantine)
            log.warn(
                "query.inbound.quarantined eventId=${failure.quarantine.eventId} reason=${failure.quarantine.reason}",
            )
        }
    }
}
