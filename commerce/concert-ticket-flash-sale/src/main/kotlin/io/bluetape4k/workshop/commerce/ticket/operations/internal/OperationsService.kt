package io.bluetape4k.workshop.commerce.ticket.operations.internal

import io.bluetape4k.workshop.commerce.ticket.operations.api.OperationsCommands
import io.bluetape4k.workshop.commerce.ticket.operations.api.OperatorReconcile
import io.bluetape4k.workshop.commerce.ticket.operations.api.ReconcileSummary
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Semaphore

enum class ReconciliationResult { CONVERGED, RETRY, QUARANTINED }
fun interface ReconciliationJob { fun run(): ReconciliationResult? }

/** Runs operator recovery under independent concurrency, batch, and wall-clock bounds. */
class OperationsService(
    private val jobs: List<ReconciliationJob>,
    operatorPermits: Int,
    private val maxBatchSize: Int,
    private val runDeadline: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : OperationsCommands {
    private val permits = Semaphore(operatorPermits, true)

    override fun reconcile(command: OperatorReconcile): ReconcileSummary {
        require(command.limit in 1..maxBatchSize)
        require(command.reason.length in 8..200)
        check(permits.tryAcquire()) { "operator_reconciliation_busy" }
        try {
            val deadline = clock.instant().plus(runDeadline)
            var processed = 0
            var converged = 0
            var quarantined = 0
            for (job in jobs) {
                if (processed >= command.limit || !clock.instant().isBefore(deadline)) break
                when (job.run() ?: continue) {
                    ReconciliationResult.CONVERGED -> converged++
                    ReconciliationResult.QUARANTINED -> quarantined++
                    ReconciliationResult.RETRY -> Unit
                }
                processed++
            }
            return ReconcileSummary(processed, converged, quarantined)
        } finally {
            permits.release()
        }
    }

    fun availablePermits(): Int = permits.availablePermits()
}
