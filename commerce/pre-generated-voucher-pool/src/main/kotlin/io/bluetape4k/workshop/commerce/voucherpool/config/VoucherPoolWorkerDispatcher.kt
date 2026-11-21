package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunOutcome
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunRequest
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunState
import org.springframework.scheduling.annotation.Scheduled

/**
 * PostgreSQL-authoritative claim을 poll합니다. Redis leadership은 duplicate trigger attempt만 억제합니다.
 * @property claims 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property trigger 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property owner 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class VoucherPoolWorkerDispatcher(
    /**
     * `claims`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val claims: JdbcVoucherPoolWorkerRepository,
    /**
     * `trigger`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val trigger: VoucherPoolWorkerTrigger,
    /**
     * `owner`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val owner: String,
) {

    private companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val MAX_CANDIDATES_PER_TICK = 16
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val MAX_OWNER_LENGTH = 128
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        const val WORKER_DISPATCH_DELAY_MILLIS = "250"
    }

    init {
        owner.requireNotBlank("owner")
        owner.length.requireLe(MAX_OWNER_LENGTH, "owner.length")
    }

    /**
     * dispatch은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Scheduled(fixedDelayString = WORKER_DISPATCH_DELAY_MILLIS)
    fun dispatch() {
        try {
            runOnce()
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            log.warn { "voucher_pool_worker_dispatch_failed failure=${failure.javaClass.simpleName}" }
        }
    }

    /**
     * runOnce은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    internal fun runOnce(): List<WorkerRunOutcome> {
        val outcomes = claims
            .findRunnable(MAX_CANDIDATES_PER_TICK)
            .mapNotNull { candidate ->
                val request = WorkerRunRequest(
                    tenantId = candidate.tenantId,
                    kind = candidate.kind,
                    scopeId = candidate.scopeId,
                    owner = owner,
                )
                trigger.runScheduled(request)
            }

        if (outcomes.isNotEmpty()) {
            log.info {
                "voucher_pool_worker_dispatch_completed " +
                        "runs=${outcomes.size} completed=${outcomes.count { it.state == WorkerRunState.COMPLETED }} " +
                        "retryable=${outcomes.count { it.state == WorkerRunState.RETRYABLE }}"
            }
        }
        return outcomes
    }
}
