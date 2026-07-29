package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import java.io.Serial
import java.io.Serializable

/** 워크숍 API가 노출하는 failure-boundary demonstration의 닫힌 catalog입니다. */
enum class JobSafetyScenario {
    CROSS_JOB_COLLISION,
    LEASE_OVERRUN,
    DYNAMIC_TENANT,
    REGION_PARTITION,
    MIXED_VERSION_ROLLOUT,
    NON_FENCEABLE_EFFECT,
}

/** production containment 또는 명시적으로 격리한 broken baseline을 선택합니다. */
enum class ScenarioMode { SAFE, UNSAFE }

/** scenario 설명 안의 제한된 low-cardinality transition 하나입니다. */
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

/** scenario 안에서 logical worker 하나가 만든 관찰 가능한 outcome입니다. */
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

/** 실행 timeline 옆에 표시할 최종 protected-resource 상태입니다. */
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

/** Spring MVC API를 통해 안전하게 serialize할 수 있는 불변 scenario 결과입니다. */
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
