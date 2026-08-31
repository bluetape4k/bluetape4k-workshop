package io.bluetape4k.workshop.leader.jobsafety.coordination

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderElectionListener
import io.bluetape4k.leader.micrometer.OBSERVATION_LEADER_AOP_EXECUTION
import io.bluetape4k.leader.micrometer.OBSERVATION_TAG_OUTCOME
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingOwnerId
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRunRequest
import io.bluetape4k.workshop.leader.jobsafety.domain.MembershipRevision
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionId
import io.bluetape4k.workshop.leader.jobsafety.domain.TenantId
import io.bluetape4k.workshop.leader.jobsafety.support.RecordingFencingLease
import io.bluetape4k.workshop.leader.jobsafety.support.RecordingLeaderElection
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

internal class JobRunCoordinatorTest {
    @Test
    fun `leader is acquired before the resource fence and both are released`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        val result = coordinator.run(request()) {
            events += "execute"
            JobMutation.Committed
        }

        result.state shouldBeEqualTo JobExecutionState.COMMITTED
        result.fencingToken?.value shouldBeEqualTo 42L
        events shouldBeEqualTo
            listOf("leader.acquire", "fence.acquire", "execute", "fence.release", "leader.release")
    }

    @Test
    fun `runWithLease exposes the request lease for an explicit user extension`() {
        val events = mutableListOf<String>()
        val expected = ExtendOutcome.Extended(Instant.parse("2026-08-31T00:00:05Z"))
        val coordinator = coordinator(events, leaderExtensionOutcome = expected)

        val result = coordinator.runWithLease(request()) { leader, _ ->
            leader.extendViaLockExtender(1.seconds) shouldBeEqualTo expected
            events += "execute"
            JobMutation.Committed
        }

        result.state shouldBeEqualTo JobExecutionState.COMMITTED
        events shouldBeEqualTo
            listOf("leader.acquire", "fence.acquire", "leader.extend", "execute", "fence.release", "leader.release")
    }

    @Test
    fun `fence contention releases the acquired leader lease without executing`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, fenceOutcome = RecordingFencingLease.Outcome.CONTENDED)

        val result = coordinator.run(request()) { error("must not execute") }

        result.state shouldBeEqualTo JobExecutionState.SKIPPED
        result.rejection shouldBeEqualTo JobRejectionReason.FENCE_CONTENDED
        events shouldBeEqualTo listOf("leader.acquire", "fence.acquire", "leader.release")
    }

    @Test
    fun `leader contention never reaches the fencing backend`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, leaderAcquired = false)

        val result = coordinator.run(request()) { error("must not execute") }

        result.state shouldBeEqualTo JobExecutionState.SKIPPED
        result.rejection shouldBeEqualTo JobRejectionReason.LEADER_CONTENDED
        events shouldBeEqualTo listOf("leader.acquire")
    }

    @Test
    fun `fencing backend failure never falls through to domain execution`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, fenceOutcome = RecordingFencingLease.Outcome.BACKEND_FAILURE)

        val result = coordinator.run(request()) { error("must not execute") }

        result.state shouldBeEqualTo JobExecutionState.FAILED
        result.rejection shouldBeEqualTo JobRejectionReason.FENCE_BACKEND_FAILURE
        events shouldBeEqualTo listOf("leader.acquire", "fence.acquire", "leader.release")
    }

    @Test
    fun `domain failure releases both leases and becomes a stable result`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        val result = coordinator.run(request()) {
            events += "execute"
            error("domain failed")
        }

        result.state shouldBeEqualTo JobExecutionState.FAILED
        result.rejection shouldBeEqualTo JobRejectionReason.DOMAIN_FAILURE
        events shouldBeEqualTo
            listOf("leader.acquire", "fence.acquire", "execute", "fence.release", "leader.release")
    }

    @Test
    fun `release failures do not replace an already committed result`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, leaderReleaseFailure = true, fenceReleaseFailure = true)

        val result = coordinator.run(request()) {
            events += "execute"
            JobMutation.Committed
        }

        result.state shouldBeEqualTo JobExecutionState.COMMITTED
        events shouldBeEqualTo
            listOf("leader.acquire", "fence.acquire", "execute", "fence.release", "leader.release")
    }

    @Test
    fun `interruption is propagated after both leases are released`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        try {
            assertFailsWith<InterruptedException> {
                coordinator.run(request()) {
                    events += "execute"
                    throw InterruptedException("cancelled")
                }
            }
            Thread.currentThread().isInterrupted shouldBeEqualTo true
            events shouldBeEqualTo
                listOf("leader.acquire", "fence.acquire", "execute", "fence.release", "leader.release")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `success error and cancellation produce lifecycle observations without raw identifiers`() {
        val handler = CollectingObservationHandler()
        val registry = ObservationRegistry.create().also { it.observationConfig().observationHandler(handler) }
        val recorder = MicrometerObservationLeaderAopMetricsRecorder(
            registry = registry,
            options = LeaderObservationOptions(includeLockName = true, includeLeaderId = true),
        )
        val listener = MicrometerObservationLeaderElectionListener(registry)

        val success = coordinator(mutableListOf(), recorder = recorder, listener = listener)
        success.run(request()) { JobMutation.Committed }
        handler.executionOutcomes() shouldBeEqualTo listOf("success")

        val failure = coordinator(mutableListOf(), recorder = recorder, listener = listener)
        failure.run(request()) { error("domain failed") }
        handler.executionOutcomes() shouldBeEqualTo listOf("success", "error")

        val cancelled = coordinator(mutableListOf(), recorder = recorder, listener = listener)
        assertFailsWith<CancellationException> {
            cancelled.run(request()) { throw CancellationException("cancelled") }
        }
        handler.executionOutcomes() shouldBeEqualTo listOf("success", "error", "cancelled")

        val backendFailure = coordinator(
            mutableListOf(),
            fenceOutcome = RecordingFencingLease.Outcome.BACKEND_FAILURE,
            recorder = recorder,
            listener = listener,
        )
        backendFailure.run(request()) { error("must not execute") }
            .state shouldBeEqualTo JobExecutionState.FAILED
        handler.executionOutcomes() shouldBeEqualTo listOf("success", "error", "cancelled", "error")

        val execution = handler.stopped.last { it.name == OBSERVATION_LEADER_AOP_EXECUTION }
        execution.high["lock.name"] shouldBeEqualTo "redacted-lock"
        execution.high["leader.id"] shouldBeEqualTo "redacted-leader"
    }

    private fun coordinator(
        events: MutableList<String>,
        leaderAcquired: Boolean = true,
        leaderReleaseFailure: Boolean = false,
        fenceOutcome: RecordingFencingLease.Outcome = RecordingFencingLease.Outcome.ACQUIRED,
        fenceReleaseFailure: Boolean = false,
        leaderExtensionOutcome: ExtendOutcome = ExtendOutcome.Rejected,
        recorder: LeaderAopMetricsRecorder = LeaderAopMetricsRecorder.NoOp,
        listener: LeaderElectionListener = NoOpLeaderElectionListener,
    ): JobRunCoordinator =
        JobRunCoordinator(
            leaderElection = RecordingLeaderElection(
                events,
                leaderAcquired,
                leaderReleaseFailure,
                leaderExtensionOutcome,
            ),
            fencingLease = RecordingFencingLease(events, fenceOutcome, fenceReleaseFailure),
            fencingTtl = Duration.ofSeconds(5),
            observationRecorder = recorder,
            observationListener = listener,
        )

    private fun request(): JobRunRequest =
        JobRunRequest(
            jobName = JobName("monthly-summary"),
            tenantId = TenantId("tenant-a"),
            conflictKey = ConflictKey.summary(TenantId("tenant-a"), YearMonth.of(2026, 7)),
            fencingOwnerId = FencingOwnerId("fence-owner"),
            membershipRevision = MembershipRevision(3L),
            regionId = RegionId("region-a"),
            regionEpoch = RegionEpoch(2L),
            namespaceEpoch = NamespaceEpoch(1L),
            contractVersion = ExecutionContractVersion(2),
            operationId = OperationId("operation-1"),
            nextValue = 100L,
        )

    private object NoOpLeaderElectionListener : LeaderElectionListener

    private class CollectingObservationHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<Snapshot>()

        override fun supportsContext(context: Observation.Context): Boolean = true

        override fun onStop(context: Observation.Context) {
            stopped += Snapshot(
                name = context.name.orEmpty(),
                low = context.lowCardinalityKeyValues.associate { it.key to it.value },
                high = context.highCardinalityKeyValues.associate { it.key to it.value },
            )
        }

        fun executionOutcomes(): List<String> = stopped
            .filter { it.name == OBSERVATION_LEADER_AOP_EXECUTION }
            .map { it.low.getValue(OBSERVATION_TAG_OUTCOME) }
    }

    private data class Snapshot(
        val name: String,
        val low: Map<String, String>,
        val high: Map<String, String>,
    )
}
