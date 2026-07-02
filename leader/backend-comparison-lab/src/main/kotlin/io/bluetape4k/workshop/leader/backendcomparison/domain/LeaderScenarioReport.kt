package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import java.io.Serializable

/**
 * One event emitted by a deterministic leader scenario.
 */
data class LeaderScenarioEvent(
    val actor: String,
    val outcome: String,
    val detail: String,
) : Serializable {

    init {
        actor.requireNotBlank("actor")
        outcome.requireNotBlank("outcome")
        detail.requireNotBlank("detail")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Learner-facing report for a deterministic leader scenario run.
 */
data class LeaderScenarioReport(
    val backendId: String,
    val scenario: LeaderScenarioKind,
    val handoffTrigger: String,
    val events: List<LeaderScenarioEvent>,
    val metricsToInspect: List<String>,
    val summary: String,
) : Serializable {

    init {
        backendId.requireNotBlank("backendId")
        handoffTrigger.requireNotBlank("handoffTrigger")
        events.requireNotEmpty("events")
        metricsToInspect.requireNotEmpty("metricsToInspect")
        summary.requireNotBlank("summary")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
