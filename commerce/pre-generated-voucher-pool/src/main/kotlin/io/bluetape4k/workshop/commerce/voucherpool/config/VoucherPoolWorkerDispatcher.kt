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

/** PostgreSQL-authoritative claim을 poll합니다. Redis leadership은 duplicate trigger attempt만 억제합니다. */
internal class VoucherPoolWorkerDispatcher(
    private val claims: JdbcVoucherPoolWorkerRepository,
    private val trigger: VoucherPoolWorkerTrigger,
    private val owner: String,
) {

    private companion object: KLogging() {
        const val MAX_CANDIDATES_PER_TICK = 16
        const val MAX_OWNER_LENGTH = 128
        const val WORKER_DISPATCH_DELAY_MILLIS = "250"
    }

    init {
        owner.requireNotBlank("owner")
        owner.length.requireLe(MAX_OWNER_LENGTH, "owner.length")
    }

    @Scheduled(fixedDelayString = WORKER_DISPATCH_DELAY_MILLIS)
    fun dispatch() {
        try {
            runOnce()
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            log.warn { "voucher_pool_worker_dispatch_failed failure=${failure.javaClass.simpleName}" }
        }
    }

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
