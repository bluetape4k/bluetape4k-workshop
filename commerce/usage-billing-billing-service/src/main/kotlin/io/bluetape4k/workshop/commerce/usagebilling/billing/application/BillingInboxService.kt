package io.bluetape4k.workshop.commerce.usagebilling.billing.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxOutcome
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
/**
 * BillingInboxService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property journal 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
class BillingInboxService(
    /**
     * `journal`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val journal: BillingInboxJournal,
) {

    companion object: KLogging()

    /**
     * `handle`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = handle(event = event)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun handle(event: BillingInboxEvent): BillingInboxOutcome =
        journal.digestFor(event.eventId)
            ?.let { existingDigest ->
                if (existingDigest == event.payloadDigest) {
                    BillingInboxOutcome.DUPLICATE
                } else {
                    BillingInboxOutcome.QUARANTINED
                }
            }
            ?: handleUnseen(event)

    /**
     * handleUnseen은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun handleUnseen(event: BillingInboxEvent): BillingInboxOutcome {
        if (journal.priceEvidence(event.tenantId, event.meterCode, event.currency) == null) {
            return BillingInboxOutcome.DEFERRED
        }

        return when (event.aggregateVersion) {
            journal.expectedVersion(event.tenantId, event.aggregateId) -> {
                journal.apply(event)
                BillingInboxOutcome.APPLIED
            }
            in Long.MIN_VALUE until journal.expectedVersion(event.tenantId, event.aggregateId) ->
                BillingInboxOutcome.DUPLICATE
            else                                                       ->
                BillingInboxOutcome.DEFERRED
        }
    }
}
