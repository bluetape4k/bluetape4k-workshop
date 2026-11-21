package io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireEquals
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherCodeKeyVersions
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherEvent
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.*

/**
 * `VOUCHER_STREAM_TYPE`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
internal const val VOUCHER_STREAM_TYPE = "voucher"
/**
 * SerializedVoucherEvent은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property eventType 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property payload 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 */


internal data class SerializedVoucherEvent(
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
 * VoucherEventCodec은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class VoucherEventCodec {
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
    fun encode(event: VoucherEvent): SerializedVoucherEvent = when (event) {
        is VoucherEvent.VoucherIssued    -> SerializedVoucherEvent(
            "voucher.issued",
            EventPayload(
                mapper.writeValueAsString(
                    VoucherIssuedPayload(
                        campaignId = event.campaignId,
                        subjectId = event.subjectId,
                        generationKeyVersion = event.codeKeyVersions.generation,
                        verificationKeyVersion = event.codeKeyVersions.verification,
                    ),
                ),
            ),
        )

        is VoucherEvent.VoucherAllocated -> SerializedVoucherEvent(
            "voucher.allocated",
            EventPayload(mapper.writeValueAsString(VoucherAllocatedPayload(event.policyVersion, event.expiresAt))),
        )

        is VoucherEvent.VoucherRedeemed  -> SerializedVoucherEvent(
            "voucher.redeemed",
            EventPayload(mapper.writeValueAsString(VoucherRedeemedPayload(event.redeemedAt))),
        )

        VoucherEvent.VoucherReleased     -> SerializedVoucherEvent("voucher.released", EventPayload("{}"))
        VoucherEvent.VoucherExpired      -> SerializedVoucherEvent("voucher.expired", EventPayload("{}"))
        VoucherEvent.VoucherRevoked      -> SerializedVoucherEvent("voucher.revoked", EventPayload("{}"))
    }

    /**
     * decode은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun decode(event: EventEnvelope): VoucherEvent? {
        if (event.stream.type != VOUCHER_STREAM_TYPE) return null
        event.schemaVersion.requireEquals(1, "${event.eventType}.schemaVersion")
        return when (event.eventType) {
            "voucher.issued"    ->
                mapper.readValue<VoucherIssuedPayload>(event.payload.canonicalJson)
                    .let { payload ->
                        VoucherEvent.VoucherIssued(
                            tenantId = event.tenantId,
                            campaignId = payload.campaignId,
                            voucherId = event.stream.id,
                            subjectId = payload.subjectId,
                            codeKeyVersions = VoucherCodeKeyVersions(
                                generation = payload.generationKeyVersion,
                                verification = payload.verificationKeyVersion,
                            ),
                        )
                    }

            "voucher.allocated" ->
                mapper.readValue<VoucherAllocatedPayload>(event.payload.canonicalJson)
                    .let { VoucherEvent.VoucherAllocated(it.policyVersion, it.expiresAt) }

            "voucher.redeemed"  ->
                mapper.readValue<VoucherRedeemedPayload>(event.payload.canonicalJson)
                    .let { VoucherEvent.VoucherRedeemed(it.redeemedAt) }

            "voucher.released"  -> VoucherEvent.VoucherReleased
            "voucher.expired"   -> VoucherEvent.VoucherExpired
            "voucher.revoked"   -> VoucherEvent.VoucherRevoked
            else                -> error("unsupported voucher event type")
        }
    }
}
/**
 * VoucherIssuedPayload은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property subjectId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property generationKeyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property verificationKeyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


private data class VoucherIssuedPayload(
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `subjectId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val subjectId: UUID,
    /**
     * `generationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generationKeyVersion: Int,
    /**
     * `verificationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val verificationKeyVersion: Int,
)
/**
 * VoucherAllocatedPayload은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


private data class VoucherAllocatedPayload(
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant,
)
/**
 * VoucherRedeemedPayload은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property redeemedAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


private data class VoucherRedeemedPayload(
    /**
     * `redeemedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val redeemedAt: Instant,
)
