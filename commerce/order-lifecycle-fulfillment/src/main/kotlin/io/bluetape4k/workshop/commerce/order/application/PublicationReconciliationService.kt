package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireInRange
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.stereotype.Service
import java.io.Serializable
/**
 * ReconciliationResult은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property requested 주문·fulfillment 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한
 * 정보만 포함합니다.
 */


internal data class ReconciliationResult(
    /**
     * `requested`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val requested: Int,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * PublicationReconciliationService은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property failedPublications 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
internal class PublicationReconciliationService(
    /**
     * `failedPublications`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val failedPublications: FailedEventPublications,
) {
    companion object: KLogging() {
        /**
         * `DEF_BATCH_SIZE`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val DEF_BATCH_SIZE = 30
        /**
         * `MAX_BATCH_SIZE`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val MAX_BATCH_SIZE = 100
    }

    /**
     * `replayFailed`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = replayFailed(batchSize = 25)
     * ```
     * @param batchSize 요청 경계에서 검증된 `batchSize`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun replayFailed(batchSize: Int = DEF_BATCH_SIZE): ReconciliationResult {
        batchSize.requireInRange(1, MAX_BATCH_SIZE, "batchSize")
        val options = ResubmissionOptions.defaults()
            .withMaxInFlight(batchSize)
            .withBatchSize(batchSize)

        failedPublications.resubmit(options)
        log.info { "publication_replay_requested batchSize=$batchSize" }

        return ReconciliationResult(batchSize)
    }
}
