package io.bluetape4k.workshop.leader.jobsafety.execution

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingOwnerId
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
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
internal class JobAuthorityPostgresIntegrationTest {
    @Test
    fun `current PostgreSQL authority maps every stale topology to a stable reason`() {
        val outcomes =
            listOf(
                executeCase(request("membership")) { fixture ->
                    fixture.deactivateTenant(TENANT_ID, MembershipRevision(8L))
                },
                executeCase(request("region").copy(run = request("region").run.copy(regionId = RegionId("region-b")))),
                executeCase(
                    request("version").copy(
                        run = request("version").run.copy(contractVersion = ExecutionContractVersion(1)),
                    ),
                ),
                executeCase(
                    request("namespace").copy(
                        run = request("namespace").run.copy(namespaceEpoch = NamespaceEpoch(1L)),
                    ),
                ),
            )

        outcomes shouldBeEqualTo
            listOf(
                JobRejectionReason.STALE_MEMBERSHIP,
                JobRejectionReason.WRONG_REGION,
                JobRejectionReason.INCOMPATIBLE_VERSION,
                JobRejectionReason.STALE_NAMESPACE,
            )
    }

    private fun executeCase(
        request: FencedJobRequest,
        beforeCommit: (JobSafetyDatabaseFixture) -> Unit = {},
    ): JobRejectionReason? =
        JobSafetyDatabaseFixture().use { fixture ->
            fixture.seedAuthority(authority())
            fixture.seedResource(CONFLICT_KEY, NamespaceEpoch(2L))
            val service = FencedJobExecutionService(fixture.executor, JobSafetyRepositories(fixture.executor))
            beforeCommit(fixture)
            service.execute(request).rejection
        }

    private fun request(operation: String): FencedJobRequest =
        FencedJobRequest(
            run =
                JobRunRequest(
                    jobName = JobName("authority-summary"),
                    tenantId = TENANT_ID,
                    conflictKey = CONFLICT_KEY,
                    fencingOwnerId = FencingOwnerId("owner-$operation"),
                    membershipRevision = MembershipRevision(7L),
                    regionId = RegionId("region-a"),
                    regionEpoch = RegionEpoch(3L),
                    namespaceEpoch = NamespaceEpoch(2L),
                    contractVersion = ExecutionContractVersion(2),
                    operationId = OperationId(operation),
                    nextValue = 10L,
                ),
            fencingToken = FencingToken(10L),
        )

    private fun authority(): JobAuthoritySeed =
        JobAuthoritySeed(
            tenantId = TENANT_ID,
            membershipRevision = MembershipRevision(7L),
            regionId = RegionId("region-a"),
            regionEpoch = RegionEpoch(3L),
            namespaceEpoch = NamespaceEpoch(2L),
            minimumWriterVersion = ExecutionContractVersion(2),
            checkpointSchemaVersion = 1,
        )

    companion object {
        private val TENANT_ID = TenantId("tenant-a")
        private val CONFLICT_KEY = ConflictKey.summary(TENANT_ID, YearMonth.of(2026, 7))
    }
}
