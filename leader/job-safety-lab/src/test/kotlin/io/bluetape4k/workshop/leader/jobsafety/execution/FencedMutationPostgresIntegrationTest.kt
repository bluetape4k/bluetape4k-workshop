package io.bluetape4k.workshop.leader.jobsafety.execution

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingOwnerId
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
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
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobAuthoritySeed
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyDatabaseFixture
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyRepositories
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.YearMonth

@Tag("integration")
internal class FencedMutationPostgresIntegrationTest {
    @Test
    fun `independent workers cannot overwrite a committed higher generation`() {
        JobSafetyDatabaseFixture().use { fixture ->
            val repositories = JobSafetyRepositories(fixture.executor)
            fixture.seedAuthority(authority())
            fixture.seedResource(CONFLICT_KEY, NamespaceEpoch(2L))
            val firstWorker = FencedJobExecutionService(fixture.executor, repositories)
            val staleWorker = FencedJobExecutionService(fixture.executor, repositories)

            firstWorker.execute(request(100L, "high")).state shouldBeEqualTo JobExecutionState.COMMITTED
            staleWorker.execute(request(99L, "stale")).rejection shouldBeEqualTo JobRejectionReason.STALE_FENCE

            repositories.resource.find(CONFLICT_KEY)?.lastAcceptedFence shouldBeEqualTo FencingToken(100L)
            repositories.checkpoint.countRows() shouldBeEqualTo 1L
            repositories.outbox.countRows() shouldBeEqualTo 1L
        }
    }

    private fun request(fence: Long, operation: String): FencedJobRequest =
        FencedJobRequest(
            JobRunRequest(
                jobName = JobName("monthly-summary"),
                tenantId = TenantId("tenant-a"),
                conflictKey = CONFLICT_KEY,
                fencingOwnerId = FencingOwnerId("owner-$fence"),
                membershipRevision = MembershipRevision(7L),
                regionId = RegionId("region-a"),
                regionEpoch = RegionEpoch(3L),
                namespaceEpoch = NamespaceEpoch(2L),
                contractVersion = ExecutionContractVersion(2),
                operationId = OperationId(operation),
                nextValue = fence,
            ),
            FencingToken(fence),
        )

    private fun authority(): JobAuthoritySeed =
        JobAuthoritySeed(
            tenantId = TenantId("tenant-a"),
            membershipRevision = MembershipRevision(7L),
            regionId = RegionId("region-a"),
            regionEpoch = RegionEpoch(3L),
            namespaceEpoch = NamespaceEpoch(2L),
            minimumWriterVersion = ExecutionContractVersion(2),
            checkpointSchemaVersion = 1,
        )

    companion object {
        private val CONFLICT_KEY = ConflictKey.summary(TenantId("tenant-a"), YearMonth.of(2026, 7))
    }
}
