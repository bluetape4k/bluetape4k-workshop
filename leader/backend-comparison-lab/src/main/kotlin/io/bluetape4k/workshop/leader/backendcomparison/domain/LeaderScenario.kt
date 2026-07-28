package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * comparison lab이 지원하는 결정적인 leader-election scenario입니다.
 */
enum class LeaderScenarioKind {
    STEADY_LEADER,
    CONTENTION_SKIP,
    ACTION_FAILURE,
    BACKEND_LOSS_HANDOFF,
}

/**
 * local deterministic leader failover scenario의 요청 값입니다.
 */
data class LeaderScenario(
    val backendId: String,
    val kind: LeaderScenarioKind,
    val jobName: String = "report-sync",
) : Serializable {

    init {
        backendId.requireNotBlank("backendId")
        jobName.requireNotBlank("jobName")
    }

    companion object {
        fun steadyLeader(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.STEADY_LEADER)

        fun contentionSkip(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.CONTENTION_SKIP)

        fun actionFailure(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.ACTION_FAILURE)

        fun backendLossHandoff(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.BACKEND_LOSS_HANDOFF)

        private const val serialVersionUID: Long = 1L
    }
}
