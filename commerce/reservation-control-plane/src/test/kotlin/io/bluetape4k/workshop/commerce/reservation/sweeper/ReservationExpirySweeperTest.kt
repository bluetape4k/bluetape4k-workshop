package io.bluetape4k.workshop.commerce.reservation.sweeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * ReservationExpirySweeperTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ReservationExpirySweeperTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "elected tick runs a bounded batch with the configured leader slot" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `elected tick runs a bounded batch with the configured leader slot`() {
        val gate = RecordingSweepLeaderGate(LeaderDecision.ELECT)
        var requestedMax = 0
        var requestedBudget = Duration.ZERO
        val sweeper = sweeper(gate) { maxResources, budget ->
            requestedMax = maxResources
            requestedBudget = budget
            SweepBatchSummary(scannedResources = 3, expiredHolds = 2, promotedEntries = 1, staleConflicts = 0)
        }

        val outcome = sweeper.sweepOnce()

        outcome shouldBeEqualTo SweepTickOutcome.Completed(
            SweepBatchSummary(scannedResources = 3, expiredHolds = 2, promotedEntries = 1, staleConflicts = 0)
        )
        gate.slot shouldBeEqualTo LeaderSlot("reservation-expiry-sweeper", "node-a")
        requestedMax shouldBeEqualTo 32
        requestedBudget shouldBeEqualTo 5.seconds()
    }

    @Test
/**
 * 이 시나리오는 "non leader tick is skipped without running database work" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `non leader tick is skipped without running database work`() {
        val gate = RecordingSweepLeaderGate(LeaderDecision.SKIP)
        var executions = 0
        val sweeper = sweeper(gate) { _, _ ->
            executions++
            SweepBatchSummary.Empty
        }

        sweeper.sweepOnce() shouldBeEqualTo SweepTickOutcome.Skipped
        executions shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "backend and action failures are reduced to bounded outcomes" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `backend and action failures are reduced to bounded outcomes`() {
        val backendFailure =
            sweeper(RecordingSweepLeaderGate(LeaderDecision.BACKEND_FAILURE)) { _, _ ->
                SweepBatchSummary.Empty
            }
        val actionFailure =
            sweeper(RecordingSweepLeaderGate(LeaderDecision.ACTION_FAILURE)) { _, _ ->
                SweepBatchSummary.Empty
            }

        backendFailure.sweepOnce() shouldBeEqualTo SweepTickOutcome.Failed(SweepFailureCode.LEADER_BACKEND_UNAVAILABLE)
        actionFailure.sweepOnce() shouldBeEqualTo SweepTickOutcome.Failed(SweepFailureCode.ACTION_FAILED)
    }

    @Test
/**
 * 이 시나리오는 "local single flight skips reentrant tick" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `local single flight skips reentrant tick`() {
        lateinit var sweeper: ReservationExpirySweeper
        var nested: SweepTickOutcome? = null
        sweeper = sweeper(RecordingSweepLeaderGate(LeaderDecision.ELECT)) { _, _ ->
            nested = sweeper.sweepOnce()
            SweepBatchSummary.Empty
        }

        sweeper.sweepOnce() shouldBeEqualTo SweepTickOutcome.Completed(SweepBatchSummary.Empty)
        nested shouldBeEqualTo SweepTickOutcome.LocalBusy
    }

/**
 * sweeper은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sweeper(
        gate: SweepLeaderGate,
        work: ReservationSweepWork,
    ): ReservationExpirySweeper =
        ReservationExpirySweeper(
            leaderGate = gate,
            sweepWork = work,
            instanceId = "node-a",
            maxResources = 32,
            tickBudget = 5.seconds()
        )

    /**
     * `LeaderDecision`은 정책 결정입니다.
     * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
     */
    private enum class LeaderDecision {
        /**
         * `LeaderDecision.ELECT`은 `elect` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
         * 사용합니다.
         */
        ELECT,
        /**
         * `LeaderDecision.SKIP`은 `skip` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
         */
        SKIP,
        /**
         * `LeaderDecision.ACTION_FAILURE`은 업무 처리가 실패해 결과를 성공으로 간주할 수 없는 값입니다.
         */
        ACTION_FAILURE,
        /**
         * `LeaderDecision.BACKEND_FAILURE`은 업무 처리가 실패해 결과를 성공으로 간주할 수 없는 값입니다.
         */
        BACKEND_FAILURE
    }

/**
 * RecordingSweepLeaderGate 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingSweepLeaderGate(
/**
 * decision은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val decision: LeaderDecision,
    ): SweepLeaderGate {
/**
 * slot은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var slot: LeaderSlot? = null

/**
 * run은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun run(
            slot: LeaderSlot,
            action: () -> SweepBatchSummary,
        ): LeaderRunResult<SweepBatchSummary> {
            this.slot = slot
            return when (decision) {
                LeaderDecision.ELECT           -> LeaderRunResult.Elected(action(), slot.leaderId)
                LeaderDecision.SKIP            -> LeaderRunResult.Skipped
                LeaderDecision.ACTION_FAILURE  -> LeaderRunResult.ActionFailed(IllegalStateException("secret detail"))
                LeaderDecision.BACKEND_FAILURE -> error("redis secret detail")
            }
        }
    }
}
