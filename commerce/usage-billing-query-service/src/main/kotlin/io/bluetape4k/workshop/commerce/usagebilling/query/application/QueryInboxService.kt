package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.query.config.QueryMetrics
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryApplyResult
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
/**
 * QueryInboxService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property journal 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
class QueryInboxService(
    /**
     * `journal`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val journal: QueryProjectionJournal,
    /**
     * `metrics`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: QueryMetrics,
) {

    companion object: KLogging()

    /**
     * `apply`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = apply(event = event)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun apply(event: QueryInboxEvent): QueryApplyResult {
        if (journal.hasEvent(event.eventId)) {
            metrics.inboxOutcome("DUPLICATE", event.eventType)
            return QueryApplyResult(applied = false)
        }
        journal.apply(event)
        metrics.inboxOutcome("APPLIED", event.eventType)

        return QueryApplyResult(applied = true)
    }
}
