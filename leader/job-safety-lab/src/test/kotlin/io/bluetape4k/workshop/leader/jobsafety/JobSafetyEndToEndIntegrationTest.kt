package io.bluetape4k.workshop.leader.jobsafety

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.ExtendOutcome
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
import io.bluetape4k.workshop.leader.jobsafety.audit.JobSafetyAuditReportPort
import io.bluetape4k.workshop.leader.jobsafety.coordination.LeaderElectionPort
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobAssignmentEntity
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobResourceEntity
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobRolloutMarkerEntity
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobRolloutMarkerRepository
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyRepositories
import io.bluetape4k.workshop.leader.jobsafety.support.AbstractJobSafetyIntegrationTest
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@Import(JobSafetyEndToEndIntegrationTest.ObservationTestConfiguration::class)
internal class JobSafetyEndToEndIntegrationTest : AbstractJobSafetyIntegrationTest() {
    @Autowired
    private lateinit var fences: FencingLeasePort

    @Autowired
    private lateinit var executionService: FencedJobExecutionService

    @Autowired
    private lateinit var repositories: JobSafetyRepositories

    @Autowired
    private lateinit var leaderElection: LeaderElectionPort

    @Autowired
    private lateinit var auditReport: JobSafetyAuditReportPort

    @Autowired
    private lateinit var observationRegistry: ObservationRegistry

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

    @Test
    fun `real redis leader lifecycle is exported while postgres remains authoritative`() {
        seedAuthorityAndResource()

        val lease = leaderElection.tryAcquire(JobName("audit-report"))
            ?: error("expected audit-report leader lease")
        lease.release()

        await
            .atMost(5.seconds)
            .untilAsserted {
                val report = auditReport.report()
                report.transport shouldBeEqualTo "MEMORY"
                (report.snapshot.accepted >= 4L).shouldBeTrue()
                report.recentEvents.any { it.path("kind").asText() == "SINGLE" }.shouldBeTrue()
                report.recentEvents.none { event ->
                    event.toString().contains("job-safety:audit-report") ||
                        event.toString().contains("audit-report")
                }.shouldBeTrue()
            }

        repositories.resource.find(CONFLICT_KEY)?.lastAcceptedFence shouldBeEqualTo null
        repositories.resource.find(CONFLICT_KEY)?.summaryValue shouldBeEqualTo 0L
    }

    @Test
    fun `real redis lease extension emits user and watchdog observations`() {
        val handler = CollectingObservationHandler()
        observationRegistry.observationConfig().observationHandler(handler)
        val lease = requireNotNull(leaderElection.tryAcquire(JobName("lease-observation")))

        try {
            val userOutcome = lease.extendViaLockExtender(1.seconds)

            (userOutcome is ExtendOutcome.Extended).shouldBeTrue()
            await.atMost(5.seconds).untilAsserted {
                val userObservation = handler.stopped.first { it.low["source"] == "user" }
                userObservation.name shouldBeEqualTo "bluetape4k.leader.lease.extension"
                userObservation.low["outcome"] shouldBeEqualTo "extended"
                userObservation.low["result"] shouldBeEqualTo "success"
            }
            await.atMost(7.seconds).untilAsserted {
                handler.stopped.any {
                    it.name == "bluetape4k.leader.lease.extension" &&
                        it.low["source"] == "watchdog"
                }.shouldBeTrue()
            }
        } finally {
            lease.release()
        }
    }

    private class CollectingObservationHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<Snapshot>()

        override fun supportsContext(context: Observation.Context): Boolean = true

        override fun onStop(context: Observation.Context) {
            stopped += Snapshot(
                name = context.name.orEmpty(),
                low = context.lowCardinalityKeyValues.associate { it.key to it.value },
            )
        }
    }

    private data class Snapshot(
        val name: String,
        val low: Map<String, String>,
    )

    @TestConfiguration(proxyBeanMethods = false)
    private class ObservationTestConfiguration {
        @Bean
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.create()
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
