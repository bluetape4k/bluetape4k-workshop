package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.support.requireGt
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import io.bluetape4k.workshop.leader.jobsafety.domain.TenantId
import java.time.YearMonth

/** Produces deterministic unsafe/safe comparisons without wall-clock sleeps. */
class JobSafetyScenarioService(
    private val timelineLimit: Int = 128,
) {
    private val unsafe = UnsafeScenarioAdapter()

    init {
        timelineLimit.requireGt(0, "timelineLimit")
    }

    fun run(scenario: JobSafetyScenario, mode: ScenarioMode): JobSafetyScenarioSnapshot {
        val draft =
            when (scenario) {
                JobSafetyScenario.CROSS_JOB_COLLISION -> crossJobCollision(mode)
                JobSafetyScenario.LEASE_OVERRUN -> leaseOverrun(mode)
                else -> error("scenario_not_implemented:$scenario")
            }
        val numbered = draft.events.mapIndexed { index, event -> event.copy(sequence = index + 1) }
        return JobSafetyScenarioSnapshot(
            scenario = scenario,
            mode = mode,
            expectedSummary = draft.expectedSummary,
            finalSummary = draft.finalSummary,
            resource = draft.resource,
            executions = draft.executions,
            timeline = numbered.take(timelineLimit),
            droppedTimelineEvents = (numbered.size - timelineLimit).coerceAtLeast(0),
        )
    }

    private fun crossJobCollision(mode: ScenarioMode): ScenarioDraft {
        if (mode == ScenarioMode.UNSAFE) return unsafe.crossJobCollision(CONFLICT_KEY)

        return ScenarioDraft(
            expectedSummary = 30L,
            finalSummary = 30L,
            resource = ScenarioResource(CONFLICT_KEY, 30L, FencingToken(42L)),
            executions =
                listOf(
                    ScenarioExecution(
                        JobName("daily-summary"),
                        CONFLICT_KEY,
                        FencingToken(41L),
                        JobExecutionState.COMMITTED,
                    ),
                    ScenarioExecution(
                        JobName("backfill-summary"),
                        CONFLICT_KEY,
                        FencingToken(42L),
                        JobExecutionState.COMMITTED,
                    ),
                ),
            events =
                listOf(
                    event("DERIVE_SHARED_KEY", JobExecutionState.REQUESTED, "both jobs derive one business key"),
                    event("DAILY_FENCE_41", JobExecutionState.FENCE_ACQUIRED, "daily acquires resource fence 41"),
                    event("DAILY_COMMIT_10", JobExecutionState.COMMITTED, "database accepts generation 41"),
                    event("BACKFILL_FENCE_42", JobExecutionState.FENCE_ACQUIRED, "backfill acquires generation 42"),
                    event("BACKFILL_COMMIT_30", JobExecutionState.COMMITTED, "database applies the accumulated summary"),
                ),
        )
    }

    private fun leaseOverrun(mode: ScenarioMode): ScenarioDraft {
        if (mode == ScenarioMode.UNSAFE) return unsafe.leaseOverrun(CONFLICT_KEY)

        return ScenarioDraft(
            expectedSummary = 42L,
            finalSummary = 42L,
            resource = ScenarioResource(CONFLICT_KEY, 42L, FencingToken(42L)),
            executions =
                listOf(
                    ScenarioExecution(
                        JobName("worker-b"),
                        CONFLICT_KEY,
                        FencingToken(42L),
                        JobExecutionState.COMMITTED,
                    ),
                    ScenarioExecution(
                        jobName = JobName("worker-a"),
                        conflictKey = CONFLICT_KEY,
                        fencingToken = FencingToken(41L),
                        state = JobExecutionState.REJECTED,
                        rejection = JobRejectionReason.STALE_FENCE,
                    ),
                ),
            events = leaseOverrunEvents(finalState = JobExecutionState.REJECTED),
        )
    }

    companion object {
        private val CONFLICT_KEY = ConflictKey.summary(TenantId("tenant-a"), YearMonth.of(2026, 7))
    }
}
