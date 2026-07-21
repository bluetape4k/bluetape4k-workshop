package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName

/** Deliberately broken baselines kept separate from production-safe execution services. */
internal class UnsafeScenarioAdapter {
    fun crossJobCollision(conflictKey: ConflictKey): ScenarioDraft =
        ScenarioDraft(
            expectedSummary = 30L,
            finalSummary = 20L,
            resource = ScenarioResource(conflictKey, summaryValue = 20L, lastAcceptedFence = null),
            executions =
                listOf(
                    ScenarioExecution(JobName("daily-summary"), conflictKey, null, JobExecutionState.COMMITTED),
                    ScenarioExecution(JobName("backfill-summary"), conflictKey, null, JobExecutionState.COMMITTED),
                ),
            events =
                listOf(
                    event("DAILY_READ_0", JobExecutionState.RUNNING, "daily reads summary=0"),
                    event("BACKFILL_READ_0", JobExecutionState.RUNNING, "backfill reads the same summary=0"),
                    event("DAILY_WRITE_10", JobExecutionState.COMMITTED, "daily writes 10 under its job lock"),
                    event("BACKFILL_WRITE_20", JobExecutionState.COMMITTED, "backfill overwrites with 20"),
                    event("LOST_UPDATE", JobExecutionState.FAILED, "expected 30 but job-name locks never conflicted"),
                ),
        )

    fun leaseOverrun(conflictKey: ConflictKey): ScenarioDraft =
        ScenarioDraft(
            expectedSummary = 42L,
            finalSummary = 41L,
            resource = ScenarioResource(conflictKey, summaryValue = 41L, lastAcceptedFence = FencingToken(41L)),
            executions =
                listOf(
                    ScenarioExecution(
                        JobName("worker-b"),
                        conflictKey,
                        FencingToken(42L),
                        JobExecutionState.COMMITTED,
                    ),
                    ScenarioExecution(
                        JobName("worker-a"),
                        conflictKey,
                        FencingToken(41L),
                        JobExecutionState.COMMITTED,
                    ),
                ),
            events = leaseOverrunEvents(finalState = JobExecutionState.COMMITTED),
        )

    fun staleAuthority(
        conflictKey: ConflictKey,
        jobName: JobName,
        committedValue: Long,
        code: String,
    ): ScenarioDraft =
        ScenarioDraft(
            expectedSummary = 0L,
            finalSummary = committedValue,
            resource = ScenarioResource(conflictKey, committedValue, FencingToken(committedValue)),
            executions =
                listOf(
                    ScenarioExecution(
                        jobName = jobName,
                        conflictKey = conflictKey,
                        fencingToken = FencingToken(committedValue),
                        state = JobExecutionState.COMMITTED,
                    ),
                ),
            events =
                listOf(
                    event("TRIGGER_FROM_SNAPSHOT", JobExecutionState.REQUESTED, "worker trusts a stale scheduler snapshot"),
                    event(code, JobExecutionState.RUNNING, "authoritative topology changes before commit"),
                    event("SKIP_DB_RECHECK", JobExecutionState.RUNNING, "unsafe path never reloads current authority"),
                    event("STALE_WRITE_COMMITTED", JobExecutionState.COMMITTED, "stale assumptions reach business state"),
                ),
        )

    fun nonFenceableEffect(conflictKey: ConflictKey): ScenarioDraft =
        ScenarioDraft(
            expectedSummary = 1L,
            finalSummary = 2L,
            resource = ScenarioResource(conflictKey, summaryValue = 2L, lastAcceptedFence = FencingToken(42L)),
            executions =
                listOf(
                    ScenarioExecution(
                        JobName("effect-worker"),
                        conflictKey,
                        FencingToken(42L),
                        JobExecutionState.FAILED,
                    ),
                ),
            events =
                listOf(
                    event("PROVIDER_REQUEST_1", JobExecutionState.EFFECT_PENDING, "provider applies the first request"),
                    event("RESPONSE_TIMEOUT", JobExecutionState.RECONCILIATION_REQUIRED, "worker cannot observe the result"),
                    event("NEW_OPERATION_CREATED", JobExecutionState.EFFECT_PENDING, "unsafe retry invents another id"),
                    event("DUPLICATE_EFFECT", JobExecutionState.FAILED, "provider applies both independent operations"),
                ),
        )
}

internal data class ScenarioDraft(
    val expectedSummary: Long,
    val finalSummary: Long,
    val resource: ScenarioResource,
    val executions: List<ScenarioExecution>,
    val events: List<ScenarioTimelineEvent>,
)

internal fun event(code: String, state: JobExecutionState, detail: String): ScenarioTimelineEvent =
    ScenarioTimelineEvent(sequence = 0, code = code, state = state, detail = detail)

internal fun leaseOverrunEvents(finalState: JobExecutionState): List<ScenarioTimelineEvent> =
    listOf(
        event("A_ACQUIRE_41", JobExecutionState.FENCE_ACQUIRED, "worker A receives fence 41"),
        event("A_PAUSE", JobExecutionState.RUNNING, "worker A pauses after reading state"),
        event("A_EXPIRE", JobExecutionState.RUNNING, "worker A lease expires without stopping its thread"),
        event("B_ACQUIRE_42", JobExecutionState.FENCE_ACQUIRED, "worker B takes over with fence 42"),
        event("B_COMMIT", JobExecutionState.COMMITTED, "worker B commits generation 42"),
        event("A_RESUME", finalState, "worker A resumes with stale generation 41"),
    )
