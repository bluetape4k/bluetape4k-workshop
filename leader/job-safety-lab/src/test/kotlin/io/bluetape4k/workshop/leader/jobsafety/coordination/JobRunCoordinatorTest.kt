package io.bluetape4k.workshop.leader.jobsafety.coordination

import io.bluetape4k.assertions.shouldBeEqualTo
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
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.YearMonth

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

    private fun coordinator(
        events: MutableList<String>,
        leaderAcquired: Boolean = true,
        leaderReleaseFailure: Boolean = false,
        fenceOutcome: RecordingFencingLease.Outcome = RecordingFencingLease.Outcome.ACQUIRED,
        fenceReleaseFailure: Boolean = false,
    ): JobRunCoordinator =
        JobRunCoordinator(
            leaderElection = RecordingLeaderElection(events, leaderAcquired, leaderReleaseFailure),
            fencingLease = RecordingFencingLease(events, fenceOutcome, fenceReleaseFailure),
            fencingTtl = Duration.ofSeconds(5),
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
}
