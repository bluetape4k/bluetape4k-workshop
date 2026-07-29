package io.bluetape4k.workshop.commerce.reservation.sweeper

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.springframework.scheduling.annotation.Scheduled
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** scheduler log와 결정적 sweep assertion에 사용하는 bounded work summary입니다. */
data class SweepBatchSummary(
    val scannedResources: Int,
    val expiredHolds: Int,
    val promotedEntries: Int,
    val staleConflicts: Int,
) : Serializable {
    init {
        require(scannedResources >= 0) { "scannedResources must not be negative" }
        require(expiredHolds >= 0) { "expiredHolds must not be negative" }
        require(promotedEntries >= 0) { "promotedEntries must not be negative" }
        require(staleConflicts >= 0) { "staleConflicts must not be negative" }
    }

    companion object {
        private const val serialVersionUID = 1L

        @JvmField
        val Empty = SweepBatchSummary(0, 0, 0, 0)
    }
}

/** advisory scheduling gate가 성공한 뒤에만 호출되는 PostgreSQL-owned expiry batch입니다. */
fun interface ReservationSweepWork {
    fun sweep(
        maxResources: Int,
        budget: Duration,
    ): SweepBatchSummary
}

/** leader library의 elected, skipped, failed outcome을 보존하는 adapter boundary입니다. */
fun interface SweepLeaderGate {
    fun run(
        slot: LeaderSlot,
        action: () -> SweepBatchSummary,
    ): LeaderRunResult<SweepBatchSummary>
}

/** elected-null과 skipped를 합치지 않고 published leader 0.4.0 result API를 호출합니다. */
class LeaderElectorSweepGate(
    private val elector: LeaderElector,
) : SweepLeaderGate {
    companion object : KLogging()

    override fun run(
        slot: LeaderSlot,
        action: () -> SweepBatchSummary,
    ): LeaderRunResult<SweepBatchSummary> = elector.runIfLeaderResult(slot, action)
}

/** 안정적인 operational failure category입니다. 어떤 category도 PostgreSQL correctness 약화를 의미하지 않습니다. */
enum class SweepFailureCode { LEADER_BACKEND_UNAVAILABLE, ACTION_FAILED, EMPTY_RESULT }

/** local suppression과 distributed leader skip을 포함하는 scheduler tick outcome 하나입니다. */
sealed interface SweepTickOutcome : Serializable {
    data class Completed(
        val summary: SweepBatchSummary,
    ) : SweepTickOutcome {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data object Skipped : SweepTickOutcome

    data object LocalBusy : SweepTickOutcome

    data class Failed(
        val code: SweepFailureCode,
    ) : SweepTickOutcome {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * local single-flight gate와 distributed leader gate가 동의할 때만 bounded expiry batch 하나를 실행합니다.
 * duplicate safety는 계속 PostgreSQL CAS와 unique constraint가 담당합니다.
 */
class ReservationExpirySweeper(
    private val leaderGate: SweepLeaderGate,
    private val sweepWork: ReservationSweepWork,
    private val instanceId: String,
    private val maxResources: Int = 32,
    private val tickBudget: Duration = Duration.ofSeconds(5),
) {
    companion object : KLogging() {
        const val LOCK_NAME = "reservation-expiry-sweeper"
    }

    private val running = AtomicBoolean(false)
    private val slot = LeaderSlot(LOCK_NAME, instanceId)

    init {
        require(maxResources in 1..32) { "maxResources must be between 1 and 32" }
        require(!tickBudget.isNegative && !tickBudget.isZero) { "tickBudget must be positive" }
    }

    @Scheduled(
        fixedDelayString = "\${reservation.sweeper.fixed-delay:PT5S}",
        initialDelayString = "\${reservation.sweeper.initial-delay:PT5S}"
    )
    fun scheduledSweep() {
        sweepOnce()
    }

    fun sweepOnce(): SweepTickOutcome {
        if (!running.compareAndSet(false, true)) {
            log.debug { "reservation_sweep_local_busy outcome=SKIPPED" }
            return SweepTickOutcome.LocalBusy
        }

        return try {
            val leaderResult =
                try {
                    leaderGate.run(slot) { sweepWork.sweep(maxResources, tickBudget) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (_: Exception) {
                    log.warn { "reservation_sweep_backend_failed reason=LEADER_BACKEND_UNAVAILABLE" }
                    return SweepTickOutcome.Failed(SweepFailureCode.LEADER_BACKEND_UNAVAILABLE)
                }

            when (leaderResult) {
                is LeaderRunResult.Elected -> {
                    val summary = leaderResult.value
                    if (summary == null) {
                        log.warn { "reservation_sweep_failed reason=EMPTY_RESULT" }
                        SweepTickOutcome.Failed(SweepFailureCode.EMPTY_RESULT)
                    } else {
                        log.debug {
                            "reservation_sweep_completed scanned=${summary.scannedResources} " +
                                "expired=${summary.expiredHolds} promoted=${summary.promotedEntries} " +
                                "stale=${summary.staleConflicts}"
                        }
                        SweepTickOutcome.Completed(summary)
                    }
                }
                LeaderRunResult.Skipped -> {
                    log.debug { "reservation_sweep_leader_skipped outcome=SKIPPED" }
                    SweepTickOutcome.Skipped
                }
                is LeaderRunResult.ActionFailed -> {
                    log.warn { "reservation_sweep_action_failed reason=ACTION_FAILED" }
                    SweepTickOutcome.Failed(SweepFailureCode.ACTION_FAILED)
                }
            }
        } finally {
            running.set(false)
        }
    }
}
