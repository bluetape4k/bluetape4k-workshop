package io.bluetape4k.workshop.leader.jobsafety

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.coordination.FenceAcquireResult
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLease
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLeasePort
import io.bluetape4k.workshop.leader.jobsafety.coordination.redis.RedisJobFencingLeaseAdapter
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
import io.bluetape4k.workshop.leader.jobsafety.execution.FencedJobExecutionService
import io.bluetape4k.workshop.leader.jobsafety.execution.FencedJobRequest
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobAssignmentEntity
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobResourceEntity
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobRolloutMarkerEntity
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobRolloutMarkerRepository
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyRepositories
import io.bluetape4k.workshop.leader.jobsafety.support.AbstractJobSafetyIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant

internal class JobSafetyEndToEndIntegrationTest : AbstractJobSafetyIntegrationTest() {
    @Autowired
    private lateinit var fences: FencingLeasePort

    @Autowired
    private lateinit var executionService: FencedJobExecutionService

    @Autowired
    private lateinit var repositories: JobSafetyRepositories

    @Test
    fun `takeover commits the newer fence and rejects a resumed stale worker`() {
        seedAuthorityAndResource()
        val adapter = fences as RedisJobFencingLeaseAdapter
        adapter.bootstrap(CONFLICT_KEY)
        val stale = fences.acquire(CONFLICT_KEY, FencingOwnerId("worker-a"), TTL).lease()
        adapter.release(stale)
        val current = fences.acquire(CONFLICT_KEY, FencingOwnerId("worker-b"), TTL).lease()

        val currentResult = executionService.execute(FencedJobRequest(request("operation-b", 42L), current.token))
        val staleResult = executionService.execute(FencedJobRequest(request("operation-a", 41L), stale.token))

        currentResult.state shouldBeEqualTo JobExecutionState.COMMITTED
        staleResult.state shouldBeEqualTo JobExecutionState.REJECTED
        staleResult.rejection shouldBeEqualTo JobRejectionReason.STALE_FENCE
        repositories.resource.find(CONFLICT_KEY)?.lastAcceptedFence shouldBeEqualTo current.token
        repositories.resource.find(CONFLICT_KEY)?.summaryValue shouldBeEqualTo 42L
    }

    private fun seedAuthorityAndResource() {
        jdbc.transaction {
            JobAssignmentEntity.new {
                tenantId = TENANT.value
                membershipRevision = MEMBERSHIP.value
                regionId = REGION.value
                regionEpoch = REGION_EPOCH.value
                active = true
            }
            JobRolloutMarkerEntity.new {
                markerName = JobRolloutMarkerRepository.CURRENT_MARKER
                namespaceEpoch = NAMESPACE.value
                minimumWriterVersion = CONTRACT.value
                checkpointSchemaVersion = CONTRACT.value
            }
            JobResourceEntity.new {
                conflictKey = CONFLICT_KEY.value
                namespaceEpoch = NAMESPACE.value
                lastAcceptedFence = 0L
                summaryValue = 0L
                updatedAt = Instant.now()
            }
        }
    }

    private fun request(operationId: String, nextValue: Long): JobRunRequest =
        JobRunRequest(
            jobName = JobName("monthly-summary"),
            tenantId = TENANT,
            conflictKey = CONFLICT_KEY,
            fencingOwnerId = FencingOwnerId("request-$operationId"),
            membershipRevision = MEMBERSHIP,
            regionId = REGION,
            regionEpoch = REGION_EPOCH,
            namespaceEpoch = NAMESPACE,
            contractVersion = CONTRACT,
            operationId = OperationId(operationId),
            nextValue = nextValue,
        )

    private fun FenceAcquireResult.lease(): FencingLease =
        when (this) {
            is FenceAcquireResult.Acquired -> lease
            is FenceAcquireResult.AlreadyOwned -> lease
            else -> error("expected acquired fence but was $this")
        }

    companion object {
        private val TENANT = TenantId("tenant-a")
        private val MEMBERSHIP = MembershipRevision(7L)
        private val REGION = RegionId("region-a")
        private val REGION_EPOCH = RegionEpoch(3L)
        private val NAMESPACE = NamespaceEpoch(1L)
        private val CONTRACT = ExecutionContractVersion(1)
        private val CONFLICT_KEY = ConflictKey.of("summary:tenant-a:2026-07")
        private val TTL = Duration.ofSeconds(5)
    }
}
