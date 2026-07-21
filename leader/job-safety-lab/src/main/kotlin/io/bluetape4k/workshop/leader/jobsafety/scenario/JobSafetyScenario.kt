package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import java.io.Serial
import java.io.Serializable

/** Closed catalog of failure-boundary demonstrations exposed by the workshop API. */
enum class JobSafetyScenario {
    CROSS_JOB_COLLISION,
    LEASE_OVERRUN,
    DYNAMIC_TENANT,
    REGION_PARTITION,
    MIXED_VERSION_ROLLOUT,
    NON_FENCEABLE_EFFECT,
}

/** Selects production containment or an explicitly isolated broken baseline. */
enum class ScenarioMode { SAFE, UNSAFE }

/** One bounded, low-cardinality transition in a scenario explanation. */
data class ScenarioTimelineEvent(
    val sequence: Int,
    val code: String,
    val state: JobExecutionState,
    val detail: String,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Observable outcome of one logical worker in a scenario. */
data class ScenarioExecution(
    val jobName: JobName,
    val conflictKey: ConflictKey,
    val fencingToken: FencingToken?,
    val state: JobExecutionState,
    val rejection: JobRejectionReason? = null,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Final protected-resource state shown next to the execution timeline. */
data class ScenarioResource(
    val conflictKey: ConflictKey,
    val summaryValue: Long,
    val lastAcceptedFence: FencingToken?,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Immutable scenario result safe to serialize through the Spring MVC API. */
data class JobSafetyScenarioSnapshot(
    val scenario: JobSafetyScenario,
    val mode: ScenarioMode,
    val expectedSummary: Long,
    val finalSummary: Long,
    val resource: ScenarioResource,
    val executions: List<ScenarioExecution>,
    val timeline: List<ScenarioTimelineEvent>,
    val droppedTimelineEvents: Int,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}
