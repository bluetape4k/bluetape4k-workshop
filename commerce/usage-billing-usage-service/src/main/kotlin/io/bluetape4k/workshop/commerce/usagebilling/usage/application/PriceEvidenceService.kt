package io.bluetape4k.workshop.commerce.usagebilling.usage.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsagePriceEvidenceInboxRepository
import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsagePriceEvidenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
/**
 * PriceEvidenceService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property priceEvidence 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 * @property inbox 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
class PriceEvidenceService(
    /**
     * `priceEvidence`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val priceEvidence: UsagePriceEvidenceRepository,
    /**
     * `inbox`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val inbox: UsagePriceEvidenceInboxRepository,
) {

    companion object: KLogging()

    /**
     * `record`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = record(evidence = evidence)
     * ```
     * @param evidence 요청 경계에서 검증된 `evidence`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun record(evidence: PriceEvidence) {
        priceEvidence.append(evidence)
    }

    /**
     * `record`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = record(event = event)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun record(event: PriceEvidenceInboxEvent): PriceEvidenceInboxOutcome =
        inbox.record(event)
}
