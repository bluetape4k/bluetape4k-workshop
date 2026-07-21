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
import org.junit.jupiter.api.Test
import java.time.YearMonth

internal class FencedJobExecutionServiceTest {
    @Test
    fun `fence 41 is rejected after fence 42 commits`() {
        JobSafetyDatabaseFixture().use { fixture ->
            val repositories = JobSafetyRepositories(fixture.executor)
            val service = FencedJobExecutionService(fixture.executor, repositories)
            fixture.seedAuthority(authority())
            fixture.seedResource(CONFLICT_KEY, NamespaceEpoch(2L))

            service.execute(request(fence = 42L, operation = "operation-42")).state shouldBeEqualTo
                JobExecutionState.COMMITTED
            val stale = service.execute(request(fence = 41L, operation = "operation-41"))

            stale.rejection shouldBeEqualTo JobRejectionReason.STALE_FENCE
            repositories.resource.find(CONFLICT_KEY)?.lastAcceptedFence shouldBeEqualTo FencingToken(42L)
            repositories.resource.find(CONFLICT_KEY)?.summaryValue shouldBeEqualTo 42L
        }
    }

    @Test
    fun `checkpoint and outbox stay unchanged when the resource update is stale`() {
        JobSafetyDatabaseFixture().use { fixture ->
            val repositories = JobSafetyRepositories(fixture.executor)
            val service = FencedJobExecutionService(fixture.executor, repositories)
            fixture.seedAuthority(authority())
            fixture.seedResource(CONFLICT_KEY, NamespaceEpoch(2L), lastAcceptedFence = 42L)

            service.execute(request(fence = 41L, operation = "stale-operation"))

            repositories.checkpoint.countRows() shouldBeEqualTo 0L
            repositories.outbox.countRows() shouldBeEqualTo 0L
            repositories.execution.countByState(JobExecutionState.REJECTED) shouldBeEqualTo 1L
        }
    }

    @Test
    fun `transaction checks current membership region version and namespace`() {
        val cases =
            listOf(
                request(fence = 10L, operation = "stale-membership").let {
                    it.copy(run = it.run.copy(membershipRevision = MembershipRevision(6L)))
                } to JobRejectionReason.STALE_MEMBERSHIP,
                request(fence = 11L, operation = "wrong-region").let {
                    it.copy(run = it.run.copy(regionId = RegionId("region-b")))
                } to JobRejectionReason.WRONG_REGION,
                request(fence = 12L, operation = "old-writer").let {
                    it.copy(run = it.run.copy(contractVersion = ExecutionContractVersion(1)))
                } to JobRejectionReason.INCOMPATIBLE_VERSION,
                request(fence = 13L, operation = "stale-namespace").let {
                    it.copy(run = it.run.copy(namespaceEpoch = NamespaceEpoch(1L)))
                } to JobRejectionReason.STALE_NAMESPACE,
            )

        cases.forEach { (request, reason) ->
            JobSafetyDatabaseFixture().use { fixture ->
                val repositories = JobSafetyRepositories(fixture.executor)
                val service = FencedJobExecutionService(fixture.executor, repositories)
                fixture.seedAuthority(authority())
                fixture.seedResource(CONFLICT_KEY, NamespaceEpoch(2L))

                service.execute(request).rejection shouldBeEqualTo reason
                repositories.checkpoint.countRows() shouldBeEqualTo 0L
                repositories.outbox.countRows() shouldBeEqualTo 0L
            }
        }
    }

    private fun request(fence: Long, operation: String): FencedJobRequest =
        FencedJobRequest(
            run =
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
            fencingToken = FencingToken(fence),
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
