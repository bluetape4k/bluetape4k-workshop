package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireGe
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import io.bluetape4k.workshop.leader.jobsafety.domain.TenantId
import java.time.YearMonth

/** wall-clock sleep 없이 결정적인 unsafe/safe 비교를 생성합니다. */
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
                JobSafetyScenario.DYNAMIC_TENANT -> dynamicTenant(mode)
                JobSafetyScenario.REGION_PARTITION -> regionPartition(mode)
                JobSafetyScenario.MIXED_VERSION_ROLLOUT -> mixedVersionRollout(mode)
                JobSafetyScenario.NON_FENCEABLE_EFFECT -> nonFenceableEffect(mode)
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

    private fun dynamicTenant(mode: ScenarioMode): ScenarioDraft {
        if (mode == ScenarioMode.UNSAFE) {
            return unsafe.staleAuthority(CONFLICT_KEY, JobName("tenant-summary"), 7L, "TENANT_REMOVED_REVISION_8")
        }
        return rejectedAuthority(
            jobName = JobName("tenant-summary"),
            fencingToken = FencingToken(7L),
            reason = JobRejectionReason.STALE_MEMBERSHIP,
            events =
                listOf(
                    event("TRIGGER_REVISION_7", JobExecutionState.REQUESTED, "scheduler captures membership revision 7"),
                    event("TENANT_REMOVED_REVISION_8", JobExecutionState.RUNNING, "tenant is removed before commit"),
                    event("RELOAD_ASSIGNMENT", JobExecutionState.RUNNING, "transaction reads active revision 8"),
                    event("STALE_MEMBERSHIP_REJECTED", JobExecutionState.REJECTED, "revision 7 cannot commit"),
                ),
        )
    }

    private fun regionPartition(mode: ScenarioMode): ScenarioDraft {
        if (mode == ScenarioMode.UNSAFE) {
            return unsafe.staleAuthority(CONFLICT_KEY, JobName("regional-summary"), 100L, "REGION_PARTITION")
        }
        return rejectedAuthority(
            jobName = JobName("regional-summary"),
            fencingToken = FencingToken(100L),
            reason = JobRejectionReason.WRONG_REGION,
            events =
                listOf(
                    event("REGION_PARTITION", JobExecutionState.REQUESTED, "region-b elects a local leader"),
                    event("LOCAL_FENCE_100", JobExecutionState.FENCE_ACQUIRED, "local Redis issues fence 100"),
                    event("READ_WRITE_HOME", JobExecutionState.RUNNING, "PostgreSQL says region-a epoch 3 is authoritative"),
                    event("WRONG_REGION_REJECTED", JobExecutionState.REJECTED, "region-b fails closed"),
                ),
        )
    }

    private fun mixedVersionRollout(mode: ScenarioMode): ScenarioDraft {
        if (mode == ScenarioMode.UNSAFE) {
            return unsafe.staleAuthority(CONFLICT_KEY, JobName("versioned-summary"), 1L, "CHECKPOINT_SCHEMA_CHANGED")
        }
        return rejectedAuthority(
            jobName = JobName("versioned-summary"),
            fencingToken = FencingToken(1L),
            reason = JobRejectionReason.INCOMPATIBLE_VERSION,
            events =
                listOf(
                    event("EXPAND_COMPATIBLE_DEPLOY", JobExecutionState.REQUESTED, "new readers understand old checkpoints"),
                    event("CHECKPOINT_SCHEMA_2", JobExecutionState.RUNNING, "checkpoint schema advances after readers"),
                    event("MIN_WRITER_2", JobExecutionState.RUNNING, "minimum writer advances last"),
                    event("OLD_WRITER_REJECTED", JobExecutionState.REJECTED, "contract v1 can no longer commit"),
                ),
        )
    }

    private fun nonFenceableEffect(mode: ScenarioMode): ScenarioDraft {
        if (mode == ScenarioMode.UNSAFE) return unsafe.nonFenceableEffect(CONFLICT_KEY)

        return ScenarioDraft(
            expectedSummary = 1L,
            finalSummary = 1L,
            resource = ScenarioResource(CONFLICT_KEY, summaryValue = 1L, lastAcceptedFence = FencingToken(42L)),
            executions =
                listOf(
                    ScenarioExecution(
                        JobName("effect-worker"),
                        CONFLICT_KEY,
                        FencingToken(42L),
                        JobExecutionState.COMPLETED,
                    ),
                ),
            events =
                listOf(
                    event("OUTBOX_COMMIT", JobExecutionState.COMMITTED, "business state and stable operation id commit together"),
                    event("PROVIDER_APPLIED_TIMEOUT", JobExecutionState.EFFECT_PENDING, "provider applies but response is lost"),
                    event(
                        "RECONCILIATION_REQUIRED",
                        JobExecutionState.RECONCILIATION_REQUIRED,
                        "unknown is durable and never guessed",
                    ),
                    event(
                        "QUERY_ORIGINAL_OPERATION",
                        JobExecutionState.RECONCILIATION_REQUIRED,
                        "worker queries the same operation id",
                    ),
                    event("RECEIPT_CONFIRMED", JobExecutionState.COMPLETED, "one provider receipt closes the operation"),
                ),
        )
    }

    private fun rejectedAuthority(
        jobName: JobName,
        fencingToken: FencingToken,
        reason: JobRejectionReason,
        events: List<ScenarioTimelineEvent>,
    ): ScenarioDraft =
        ScenarioDraft(
            expectedSummary = 0L,
            finalSummary = 0L,
            resource = ScenarioResource(CONFLICT_KEY, 0L, null),
            executions =
                listOf(
                    ScenarioExecution(
                        jobName = jobName,
                        conflictKey = CONFLICT_KEY,
                        fencingToken = fencingToken,
                        state = JobExecutionState.REJECTED,
                        rejection = reason,
                    ),
                ),
            events = events,
        )

    companion object {
        private val CONFLICT_KEY = ConflictKey.summary(TenantId("tenant-a"), YearMonth.of(2026, 7))
    }
}

internal class RolloutProtocol {
    private var checkpointSchemaVersion: Int = 1
    private var minimumWriterVersion: ExecutionContractVersion = ExecutionContractVersion(1)

    fun advanceCheckpointSchema(nextVersion: Int) {
        nextVersion.requireGe(checkpointSchemaVersion, "checkpointSchemaVersion")
        checkpointSchemaVersion = nextVersion
    }

    fun advanceMinimumWriter(nextVersion: ExecutionContractVersion) {
        nextVersion.value.requireGe(minimumWriterVersion.value, "minimumWriterVersion")
        require(checkpointSchemaVersion >= nextVersion.value) {
            "checkpoint schema must be deployed before raising the minimum writer"
        }
        minimumWriterVersion = nextVersion
    }
}
