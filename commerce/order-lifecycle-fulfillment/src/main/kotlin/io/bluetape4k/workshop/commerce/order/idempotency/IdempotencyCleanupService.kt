package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireInRange
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * recoverable lease를 삭제하지 않고 terminal HTTP idempotency evidence를 주기적으로 제한합니다.
 * @property repository 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property batchSize 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 */
@Service
internal class IdempotencyCleanupService(
    /**
     * `repository`은 주문·결제·재고·배송에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: HttpIdempotencyRepository,
    /**
     * `clock`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
    @param:Value("\${order-lifecycle.idempotency.cleanup-batch-size:250}")
    /**
     * `batchSize`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val batchSize: Int,
) {
    companion object: KLogging()

    init {
        batchSize.requireInRange(1, HttpIdempotencyRepository.MAX_CLEANUP_BATCH, "batchSize")
    }

    /**
     * deleteExpiredTerminal은 주문 라이프사이클과 fulfillment의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된
     * 요청은
     * 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Scheduled(
        fixedDelayString = "\${order-lifecycle.idempotency.cleanup-interval:1h}",
        initialDelayString = "\${order-lifecycle.idempotency.cleanup-initial-delay:1m}"
    )
    @Transactional
    /**
     * deleteExpiredTerminal은 주문 이행 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    fun deleteExpiredTerminal(): Int =
        repository.deleteExpiredTerminal(Instant.now(clock), batchSize)
            .also { deleted ->
                if (deleted > 0) {
                    log.info { "idempotency_cleanup_completed deleted=$deleted" }
                }
            }
}
