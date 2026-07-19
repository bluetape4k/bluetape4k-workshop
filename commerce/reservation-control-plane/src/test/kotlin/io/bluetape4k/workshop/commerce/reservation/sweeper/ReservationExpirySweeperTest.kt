package io.bluetape4k.workshop.commerce.reservation.sweeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import org.junit.jupiter.api.Test
import java.time.Duration

class ReservationExpirySweeperTest {
    @Test
    fun `elected tick runs a bounded batch with the configured leader slot`() {
        val gate = RecordingSweepLeaderGate(LeaderDecision.ELECT)
        var requestedMax = 0
        var requestedBudget = Duration.ZERO
        val sweeper =
            sweeper(gate) { maxResources, budget ->
                requestedMax = maxResources
                requestedBudget = budget
                SweepBatchSummary(scannedResources = 3, expiredHolds = 2, promotedEntries = 1, staleConflicts = 0)
            }

        val outcome = sweeper.sweepOnce()

        outcome shouldBeEqualTo
            SweepTickOutcome.Completed(
                SweepBatchSummary(scannedResources = 3, expiredHolds = 2, promotedEntries = 1, staleConflicts = 0)
            )
        gate.slot shouldBeEqualTo LeaderSlot("reservation-expiry-sweeper", "node-a")
        requestedMax shouldBeEqualTo 32
        requestedBudget shouldBeEqualTo Duration.ofSeconds(5)
    }

    @Test
    fun `non leader tick is skipped without running database work`() {
        val gate = RecordingSweepLeaderGate(LeaderDecision.SKIP)
        var executions = 0
        val sweeper =
            sweeper(gate) { _, _ ->
                executions++
                SweepBatchSummary.Empty
            }

        sweeper.sweepOnce() shouldBeEqualTo SweepTickOutcome.Skipped
        executions shouldBeEqualTo 0
    }

    @Test
    fun `backend and action failures are reduced to bounded outcomes`() {
        val backendFailure =
            sweeper(RecordingSweepLeaderGate(LeaderDecision.BACKEND_FAILURE)) { _, _ ->
                SweepBatchSummary.Empty
            }
        val actionFailure =
            sweeper(RecordingSweepLeaderGate(LeaderDecision.ACTION_FAILURE)) { _, _ ->
                SweepBatchSummary.Empty
            }

        backendFailure.sweepOnce() shouldBeEqualTo
            SweepTickOutcome.Failed(SweepFailureCode.LEADER_BACKEND_UNAVAILABLE)
        actionFailure.sweepOnce() shouldBeEqualTo SweepTickOutcome.Failed(SweepFailureCode.ACTION_FAILED)
    }

    @Test
    fun `local single flight skips reentrant tick`() {
        lateinit var sweeper: ReservationExpirySweeper
        var nested: SweepTickOutcome? = null
        sweeper =
            sweeper(RecordingSweepLeaderGate(LeaderDecision.ELECT)) { _, _ ->
                nested = sweeper.sweepOnce()
                SweepBatchSummary.Empty
            }

        sweeper.sweepOnce() shouldBeEqualTo SweepTickOutcome.Completed(SweepBatchSummary.Empty)
        nested shouldBeEqualTo SweepTickOutcome.LocalBusy
    }

    private fun sweeper(
        gate: SweepLeaderGate,
        work: ReservationSweepWork,
    ) = ReservationExpirySweeper(
        leaderGate = gate,
        sweepWork = work,
        instanceId = "node-a",
        maxResources = 32,
        tickBudget = Duration.ofSeconds(5)
    )

    private enum class LeaderDecision { ELECT, SKIP, ACTION_FAILURE, BACKEND_FAILURE }

    private class RecordingSweepLeaderGate(
        private val decision: LeaderDecision,
    ) : SweepLeaderGate {
        var slot: LeaderSlot? = null

        override fun run(
            slot: LeaderSlot,
            action: () -> SweepBatchSummary,
        ): LeaderRunResult<SweepBatchSummary> {
            this.slot = slot
            return when (decision) {
                LeaderDecision.ELECT -> LeaderRunResult.Elected(action(), slot.leaderId)
                LeaderDecision.SKIP -> LeaderRunResult.Skipped
                LeaderDecision.ACTION_FAILURE -> LeaderRunResult.ActionFailed(IllegalStateException("secret detail"))
                LeaderDecision.BACKEND_FAILURE -> throw IllegalStateException("redis secret detail")
            }
        }
    }
}
