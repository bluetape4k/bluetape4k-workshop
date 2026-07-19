package io.bluetape4k.workshop.commerce.voucher.reconciliation

import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.config.VoucherWorkerProperties
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface VoucherLeaderRunner {
    fun run(action: () -> ReconciliationResult): LeaderRunResult<ReconciliationResult>
}

/** Lazily resolves Lettuce so a missing leader backend never blocks application startup. */
internal class LettuceVoucherLeaderRunner(
    private val electorProvider: () -> LettuceLeaderElector?,
    instanceId: String,
) : VoucherLeaderRunner {
    private val slot = LeaderSlot(LEADER_LOCK_NAME, instanceId)

    override fun run(action: () -> ReconciliationResult): LeaderRunResult<ReconciliationResult> {
        val elector = electorProvider() ?: throw LeaderBackendUnavailableException()
        return elector.runIfLeaderResult(slot, action)
    }

    private class LeaderBackendUnavailableException : IllegalStateException("leader backend unavailable")

    companion object {
        private const val LEADER_LOCK_NAME = "voucher-reconciliation"
    }
}

internal sealed interface WorkerRunResult {
    data class Elected(
        val result: ReconciliationResult,
        val leaderId: String?,
    ) : WorkerRunResult

    data class Manual(val result: ReconciliationResult) : WorkerRunResult

    data object LeaderSkipped : WorkerRunResult

    data object LeaderBackendUnavailable : WorkerRunResult

    data object LeaderActionFailed : WorkerRunResult

    data object LocalRunInProgress : WorkerRunResult
}

/** Shares one local single-flight guard between scheduled and operator-triggered runs. */
internal class VoucherReconciliationWorker(
    private val reconciliation: VoucherReconciliationService,
    private val properties: VoucherWorkerProperties,
    private val leader: VoucherLeaderRunner,
) {
    private val running = AtomicBoolean()

    fun runScheduled(): WorkerRunResult =
        singleFlight {
            try {
                when (val result = leader.run(::runService)) {
                    is LeaderRunResult.Elected -> {
                        WorkerRunResult.Elected(checkNotNull(result.value), result.leaderId)
                    }
                    LeaderRunResult.Skipped -> WorkerRunResult.LeaderSkipped
                    is LeaderRunResult.ActionFailed -> {
                        log.warn {
                            "voucher_reconciliation_leader_action_failed " +
                                "failure=${result.cause.javaClass.simpleName}"
                        }
                        WorkerRunResult.LeaderActionFailed
                    }
                }
            } catch (failure: Exception) {
                log.warn {
                    "voucher_reconciliation_leader_unavailable fallback=MANUAL " +
                        "failure=${failure.javaClass.simpleName}"
                }
                WorkerRunResult.LeaderBackendUnavailable
            }
        }

    fun runManual(): WorkerRunResult =
        singleFlight { WorkerRunResult.Manual(runService()) }

    private fun runService(): ReconciliationResult =
        reconciliation.runBatch(properties.batchSize, properties.runDeadline)

    private fun singleFlight(action: () -> WorkerRunResult): WorkerRunResult {
        if (!running.compareAndSet(false, true)) {
            log.info { "voucher_reconciliation_skipped reason=LOCAL_RUN_IN_PROGRESS" }
            return WorkerRunResult.LocalRunInProgress
        }
        return try {
            action()
        } finally {
            running.set(false)
        }
    }

    companion object : KLogging()
}

/** Triggers the shared single-flight worker path on Spring's Java 25 virtual-thread scheduler. */
internal class VoucherReconciliationScheduler(
    private val worker: VoucherReconciliationWorker,
) {
    @Scheduled(
        fixedDelayString = "\${workshop.voucher.worker.interval:5s}",
        initialDelayString = "\${workshop.voucher.worker.initial-delay:5s}",
    )
    fun tick() {
        worker.runScheduled()
    }
}
