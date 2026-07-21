package io.bluetape4k.workshop.leader.jobsafety.persistence

import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.MembershipRevision
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionId
import io.bluetape4k.workshop.leader.jobsafety.domain.TenantId
import org.jetbrains.exposed.v1.core.eq

data class JobAssignmentSnapshot(
    val tenantId: TenantId,
    val membershipRevision: MembershipRevision,
    val regionId: RegionId,
    val regionEpoch: RegionEpoch,
    val active: Boolean,
)

data class JobRolloutSnapshot(
    val namespaceEpoch: NamespaceEpoch,
    val minimumWriterVersion: ExecutionContractVersion,
    val checkpointSchemaVersion: Int,
)

class JobAssignmentRepository(
    private val jdbc: JobSafetyJdbcExecutor,
) : JobSafetyExposedJdbcRepository<JobAssignmentEntity, Long>(JobAssignmentEntity::class.java) {
    fun findByTenant(tenantId: TenantId): JobAssignmentSnapshot? =
        jdbc.transaction {
            findAll { JobAssignments.tenantId eq tenantId.value }
                .singleOrNull()
                ?.let {
                    JobAssignmentSnapshot(
                        tenantId = TenantId(it.tenantId),
                        membershipRevision = MembershipRevision(it.membershipRevision),
                        regionId = RegionId(it.regionId),
                        regionEpoch = RegionEpoch(it.regionEpoch),
                        active = it.active,
                    )
                }
        }
}

class JobRolloutMarkerRepository(
    private val jdbc: JobSafetyJdbcExecutor,
) : JobSafetyExposedJdbcRepository<JobRolloutMarkerEntity, Long>(JobRolloutMarkerEntity::class.java) {
    fun current(): JobRolloutSnapshot? =
        jdbc.transaction {
            findAll { JobRolloutMarkers.markerName eq CURRENT_MARKER }
                .singleOrNull()
                ?.let {
                    JobRolloutSnapshot(
                        namespaceEpoch = NamespaceEpoch(it.namespaceEpoch),
                        minimumWriterVersion = ExecutionContractVersion(it.minimumWriterVersion),
                        checkpointSchemaVersion = it.checkpointSchemaVersion,
                    )
                }
        }

    companion object {
        const val CURRENT_MARKER = "current"
    }
}

class JobResourceRepository :
    JobSafetyExposedJdbcRepository<JobResourceEntity, Long>(JobResourceEntity::class.java)

class JobExecutionRepository :
    JobSafetyExposedJdbcRepository<JobExecutionEntity, Long>(JobExecutionEntity::class.java)

class JobCheckpointRepository :
    JobSafetyExposedJdbcRepository<JobCheckpointEntity, Long>(JobCheckpointEntity::class.java)

class JobOutboxRepository :
    JobSafetyExposedJdbcRepository<JobOutboxEntity, Long>(JobOutboxEntity::class.java)

class JobEffectReceiptRepository :
    JobSafetyExposedJdbcRepository<JobEffectReceiptEntity, Long>(JobEffectReceiptEntity::class.java)

class JobSafetyRepositories(jdbc: JobSafetyJdbcExecutor) {
    val assignment = JobAssignmentRepository(jdbc)
    val rollout = JobRolloutMarkerRepository(jdbc)
    val resource = JobResourceRepository()
    val execution = JobExecutionRepository()
    val checkpoint = JobCheckpointRepository()
    val outbox = JobOutboxRepository()
    val effectReceipt = JobEffectReceiptRepository()
}
