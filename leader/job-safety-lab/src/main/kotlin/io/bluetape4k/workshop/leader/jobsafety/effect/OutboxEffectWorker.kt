package io.bluetape4k.workshop.leader.jobsafety.effect

import io.bluetape4k.workshop.leader.jobsafety.domain.EffectDeliveryState
import io.bluetape4k.workshop.leader.jobsafety.domain.ExternalEffectResult
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobEffectReceiptRepository
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobOutboxRepository
import io.bluetape4k.support.requireGt
import java.time.Duration
import java.time.Instant

enum class EffectWorkResult { NO_WORK, CONFIRMED, DECLINED, RECONCILIATION_REQUIRED }

interface EffectOperations {
    fun deliverNext(): EffectWorkResult

    fun reconcileNext(): EffectWorkResult
}

/** Claims briefly, calls the provider outside a transaction, then persists the observed result. */
class OutboxEffectWorker(
    private val outbox: JobOutboxRepository,
    private val receipts: JobEffectReceiptRepository,
    private val provider: ExternalEffectPort,
    private val claimTimeout: Duration = Duration.ofSeconds(30),
    private val clock: () -> Instant = Instant::now,
) : EffectOperations {
    init {
        claimTimeout.requireGt(Duration.ZERO, "claimTimeout")
    }

    override fun deliverNext(): EffectWorkResult {
        val claimed =
            outbox.claimNext(EffectDeliveryState.PENDING, clock(), claimTimeout)
                ?: return EffectWorkResult.NO_WORK
        return finalize(claimed.operationId, provider.execute(claimed.operationId))
    }

    override fun reconcileNext(): EffectWorkResult {
        val claimed =
            outbox.claimNextForReconciliation(clock(), claimTimeout)
                ?: return EffectWorkResult.NO_WORK
        return finalize(claimed.operationId, provider.query(claimed.operationId))
    }

    private fun finalize(operationId: OperationId, result: ExternalEffectResult): EffectWorkResult =
        when (result) {
            ExternalEffectResult.UNKNOWN -> {
                outbox.transaction {
                    outbox.complete(this, operationId, EffectDeliveryState.RECONCILIATION_REQUIRED)
                }
                EffectWorkResult.RECONCILIATION_REQUIRED
            }

            ExternalEffectResult.CONFIRMED -> {
                recordTerminal(operationId, result, EffectDeliveryState.CONFIRMED)
                EffectWorkResult.CONFIRMED
            }

            ExternalEffectResult.DECLINED -> {
                recordTerminal(operationId, result, EffectDeliveryState.DECLINED)
                EffectWorkResult.DECLINED
            }
        }

    private fun recordTerminal(
        operationId: OperationId,
        result: ExternalEffectResult,
        state: EffectDeliveryState,
    ) {
        outbox.transaction {
            outbox.complete(this, operationId, state)
            receipts.record(this, provider.providerName, operationId, result)
        }
    }
}
