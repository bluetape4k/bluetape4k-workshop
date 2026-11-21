package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentPosted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingCloseBatchRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingCloseStarted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodFinalized
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodOpened
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventMetadata
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceIssued
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PriceActivated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventCodecRegistry
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.*
/**
 * DomainEventJsonCodec은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property mapper 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class DomainEventJsonCodec(
    /**
     * `mapper`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper = Jackson.defaultJsonMapper,
) {

    private companion object: KLogging() {
        /**
         * `MAX_PAYLOAD_BYTES`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        /**
         * `MAX_METADATA_BYTES`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val MAX_METADATA_BYTES = 4 * 1024
    }
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val registry: EventCodecRegistry = EventCodecRegistry()
        .apply {
            register<MeterRegistered>()
            register<PriceActivated>()
            register<UsageAccepted>()
            register<UsageRated>()
            register<BillingPeriodOpened>()
            register<BillingCloseStarted>()
            register<BillingCloseBatchRated>()
            register<BillingPeriodFinalized>()
            register<InvoiceIssued>()
            register<AdjustmentPosted>()
        }

    /**
     * `encode`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = encode(event = event, occurredAt = occurredAt, eventId = UUID.randomUUID(),
     * metadata
     * = metadata)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @param occurredAt 요청 경계에서 검증된 `occurredAt`입니다.
     * @param eventId 요청 경계에서 검증된 `eventId`입니다.
     * @param metadata 요청 경계에서 검증된 `metadata`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun encode(
        event: DomainEvent,
        occurredAt: Instant,
        eventId: UUID = Uuid.V7.nextId(),
        metadata: EventMetadata = EventMetadata.system(eventId),
    ): NewEvent {
        /**
         * `payloadJson`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val payloadJson = mapper.writeValueAsString(event)
        /**
         * `metadataJson`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val metadataJson = mapper.writeValueAsString(metadata)
        require(payloadJson.toUtf8Bytes().size <= MAX_PAYLOAD_BYTES) { "event_payload_too_large" }
        require(metadataJson.toUtf8Bytes().size <= MAX_METADATA_BYTES) { "event_metadata_too_large" }
        return NewEvent(eventId, event, payloadJson, metadataJson, occurredAt)
    }

    /**
     * register은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private inline fun <reified E: DomainEvent> EventCodecRegistry.register() {
        val sampleType = eventTypeOf(E::class.java)
        register(sampleType, 1) { payload -> mapper.readValue(payload, E::class.java) }
    }

    /**
     * eventTypeOf은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param type 업무 처리에 필요한 type 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun eventTypeOf(type: Class<out DomainEvent>): String = when (type) {
        MeterRegistered::class.java     -> "meter.registered"
        PriceActivated::class.java      -> "price.activated"
        UsageAccepted::class.java       -> "usage.accepted"
        UsageRated::class.java          -> "usage.rated"
        BillingPeriodOpened::class.java -> "billing-period.opened"
        BillingCloseStarted::class.java -> "billing-period.close-started"
        BillingCloseBatchRated::class.java -> "billing-period.close-batch-rated"
        BillingPeriodFinalized::class.java -> "billing-period.finalized"
        InvoiceIssued::class.java       -> "invoice.issued"
        AdjustmentPosted::class.java    -> "adjustment.posted"
        else                            -> error("unsupported_event_type:${type.name}")
    }
}
