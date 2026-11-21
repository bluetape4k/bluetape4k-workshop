package io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireEquals
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.*

/**
 * `CAMPAIGN_STREAM_TYPE`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
internal const val CAMPAIGN_STREAM_TYPE = "campaign"
/**
 * SerializedCampaignEvent은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property eventType 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property payload 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 */


internal data class SerializedCampaignEvent(
    /**
     * `eventType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val eventType: String,
    /**
     * `payload`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val payload: EventPayload,
)

/**
 * CampaignEventCodec은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class CampaignEventCodec {
    /**
     * 기술 구현을 위한 내부 상태이며 업무 데이터·비밀값을 외부 응답에 포함하지 않습니다.
     */
    private val mapper = Jackson.defaultJsonMapper

    /**
     * encode은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun encode(event: CampaignEvent): SerializedCampaignEvent = when (event) {
        is CampaignEvent.CampaignCreated         ->
            SerializedCampaignEvent(
                eventType = "campaign.created",
                payload = EventPayload(
                    mapper.writeValueAsString(
                        CampaignCreatedPayload(
                            event.startsAt,
                            event.endsAt,
                            event.capacity,
                            event.perUserLimit,
                            event.redemptionTtlSeconds,
                        ),
                    ),
                ),
            )

        CampaignEvent.CampaignActivated          ->
            SerializedCampaignEvent(
                eventType = "campaign.activated",
                payload = EventPayload("{}")
            )

        is CampaignEvent.CampaignCapacityChanged ->
            SerializedCampaignEvent(
                eventType = "campaign.capacity-changed",
                payload = EventPayload(mapper.writeValueAsString(CampaignCapacityChangedPayload(event.capacity))),
            )

        is CampaignEvent.VoucherCapacityReserved ->
            SerializedCampaignEvent(
                eventType = "campaign.voucher-capacity-reserved",
                payload = EventPayload(
                    mapper.writeValueAsString(VoucherCapacityReservedPayload(event.voucherId, event.policyVersion)),
                ),
            )
    }

    /**
     * decode은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun decode(event: EventEnvelope): CampaignEvent? {
        if (event.stream.type != CAMPAIGN_STREAM_TYPE) return null
        event.schemaVersion.requireEquals(1, "${event.eventType}.schemaVersion")

        return when (event.eventType) {
            "campaign.created"                   ->
                mapper.readValue<CampaignCreatedPayload>(event.payload.canonicalJson)
                    .toEvent(event)

            "campaign.activated"                 -> CampaignEvent.CampaignActivated
            "campaign.capacity-changed"          ->
                mapper.readValue<CampaignCapacityChangedPayload>(event.payload.canonicalJson)
                    .let { CampaignEvent.CampaignCapacityChanged(it.capacity) }

            "campaign.voucher-capacity-reserved" ->
                mapper.readValue<VoucherCapacityReservedPayload>(event.payload.canonicalJson)
                    .let { CampaignEvent.VoucherCapacityReserved(it.voucherId, it.policyVersion) }

            else                                 ->
                error("unsupported campaign event type")
        }
    }
}
/**
 * CampaignCreatedPayload은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property startsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property endsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property perUserLimit 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property redemptionTtlSeconds 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


private data class CampaignCreatedPayload(
    /**
     * `startsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val startsAt: Instant,
    /**
     * `endsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val endsAt: Instant,
    /**
     * `capacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int,
    /**
     * `perUserLimit`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val perUserLimit: Int,
    /**
     * `redemptionTtlSeconds`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val redemptionTtlSeconds: Long,
) {
    /**
     * toEvent은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param envelope 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun toEvent(envelope: EventEnvelope): CampaignEvent.CampaignCreated =
        CampaignEvent.CampaignCreated(
            tenantId = envelope.tenantId,
            campaignId = envelope.stream.id,
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = capacity,
            perUserLimit = perUserLimit,
            redemptionTtlSeconds = redemptionTtlSeconds,
        )
}
/**
 * CampaignCapacityChangedPayload은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 */


private data class CampaignCapacityChangedPayload(
    /**
     * `capacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int,
)
/**
 * VoucherCapacityReservedPayload은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property voucherId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


private data class VoucherCapacityReservedPayload(
    /**
     * `voucherId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val voucherId: UUID,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
)
