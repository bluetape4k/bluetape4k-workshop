package io.bluetape4k.workshop.leader.jobsafety.persistence

import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRunRequest
import io.bluetape4k.workshop.leader.jobsafety.domain.MembershipRevision
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionId
import io.bluetape4k.workshop.leader.jobsafety.domain.TenantId
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

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

data class JobResourceSnapshot(
    val conflictKey: ConflictKey,
    val namespaceEpoch: NamespaceEpoch,
    val lastAcceptedFence: FencingToken?,
    val summaryValue: Long,
)

class JobAssignmentRepository(
    private val jdbc: JobSafetyJdbcExecutor,
) : JobSafetyExposedJdbcRepository<JobAssignmentEntity, Long>(JobAssignmentEntity::class.java) {
    fun findByTenant(tenantId: TenantId): JobAssignmentSnapshot? =
        jdbc.transaction {
            findCurrent(this, tenantId)
        }

    internal fun findCurrent(
        transaction: JobSafetyJdbcTransaction,
        tenantId: TenantId,
    ): JobAssignmentSnapshot? =
        transaction.withExposed {
            JobAssignments.selectAll()
                .where { JobAssignments.tenantId eq tenantId.value }
                .singleOrNull()
                ?.let { row ->
                    JobAssignmentSnapshot(
                        tenantId = TenantId(row[JobAssignments.tenantId]),
                        membershipRevision = MembershipRevision(row[JobAssignments.membershipRevision]),
                        regionId = RegionId(row[JobAssignments.regionId]),
                        regionEpoch = RegionEpoch(row[JobAssignments.regionEpoch]),
                        active = row[JobAssignments.active],
                    )
                }
        }
}

class JobRolloutMarkerRepository(
    private val jdbc: JobSafetyJdbcExecutor,
) : JobSafetyExposedJdbcRepository<JobRolloutMarkerEntity, Long>(JobRolloutMarkerEntity::class.java) {
    fun current(): JobRolloutSnapshot? =
        jdbc.transaction {
            findCurrent(this)
        }

    internal fun findCurrent(transaction: JobSafetyJdbcTransaction): JobRolloutSnapshot? =
        transaction.withExposed {
            JobRolloutMarkers.selectAll()
                .where { JobRolloutMarkers.markerName eq CURRENT_MARKER }
                .singleOrNull()
                ?.let { row ->
                    JobRolloutSnapshot(
                        namespaceEpoch = NamespaceEpoch(row[JobRolloutMarkers.namespaceEpoch]),
                        minimumWriterVersion = ExecutionContractVersion(row[JobRolloutMarkers.minimumWriterVersion]),
                        checkpointSchemaVersion = row[JobRolloutMarkers.checkpointSchemaVersion],
                    )
                }
        }

    companion object {
        const val CURRENT_MARKER = "current"
    }
}

class JobResourceRepository(
    private val jdbc: JobSafetyJdbcExecutor,
) : JobSafetyExposedJdbcRepository<JobResourceEntity, Long>(JobResourceEntity::class.java) {
    fun find(conflictKey: ConflictKey): JobResourceSnapshot? =
        jdbc.transaction { findCurrent(this, conflictKey) }

    internal fun findCurrent(
        transaction: JobSafetyJdbcTransaction,
        conflictKey: ConflictKey,
    ): JobResourceSnapshot? =
        transaction.withExposed {
            JobResources.selectAll()
                .where { JobResources.conflictKey eq conflictKey.value }
                .singleOrNull()
                ?.let { row ->
                    val persistedFence = row[JobResources.lastAcceptedFence]
                    JobResourceSnapshot(
                        conflictKey = ConflictKey.of(row[JobResources.conflictKey]),
                        namespaceEpoch = NamespaceEpoch(row[JobResources.namespaceEpoch]),
                        lastAcceptedFence = persistedFence.takeIf { it > 0L }?.let(::FencingToken),
                        summaryValue = row[JobResources.summaryValue],
                    )
                }
        }

    internal fun accept(
        transaction: JobSafetyJdbcTransaction,
        request: JobRunRequest,
        fencingToken: FencingToken,
        now: Instant,
    ): Int =
        transaction.withExposed {
            JobResources.update({
                (JobResources.conflictKey eq request.conflictKey.value) and
                    (JobResources.namespaceEpoch eq request.namespaceEpoch.value) and
                    (JobResources.lastAcceptedFence less fencingToken.value)
            }) {
                it[lastAcceptedFence] = fencingToken.value
                it[summaryValue] = request.nextValue
                it[updatedAt] = now
            }
        }
}

class JobExecutionRepository(
    private val jdbc: JobSafetyJdbcExecutor,
) : JobSafetyExposedJdbcRepository<JobExecutionEntity, Long>(JobExecutionEntity::class.java) {
    internal fun record(
        transaction: JobSafetyJdbcTransaction,
        request: JobRunRequest,
        fencingToken: FencingToken,
        state: JobExecutionState,
        rejection: JobRejectionReason?,
        now: Instant,
    ) {
        transaction.withExposed {
            JobExecutions.insert {
                it[operationId] = request.operationId.value
                it[jobName] = request.jobName.value
                it[tenantId] = request.tenantId.value
                it[conflictKey] = request.conflictKey.value
                it[fencingOwnerId] = request.fencingOwnerId.value
                it[JobExecutions.fencingToken] = fencingToken.value
                it[JobExecutions.state] = state.name
                it[JobExecutions.rejection] = rejection?.name
                it[contractVersion] = request.contractVersion.value
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    fun countByState(state: JobExecutionState): Long =
        jdbc.transaction {
            withExposed {
                JobExecutions.selectAll().where { JobExecutions.state eq state.name }.count()
            }
        }
}

class JobCheckpointRepository(
    private val jdbc: JobSafetyJdbcExecutor,
) : JobSafetyExposedJdbcRepository<JobCheckpointEntity, Long>(JobCheckpointEntity::class.java) {
    internal fun upsert(
        transaction: JobSafetyJdbcTransaction,
        request: JobRunRequest,
        fencingToken: FencingToken,
        schemaVersion: Int,
        now: Instant,
    ) {
        transaction.withExposed {
            val updated =
                JobCheckpoints.update({ JobCheckpoints.conflictKey eq request.conflictKey.value }) {
                    it[JobCheckpoints.fencingToken] = fencingToken.value
                    it[JobCheckpoints.schemaVersion] = schemaVersion
                    it[summaryValue] = request.nextValue
                    it[updatedAt] = now
                }
            if (updated == 0) {
                JobCheckpoints.insert {
                    it[conflictKey] = request.conflictKey.value
                    it[JobCheckpoints.fencingToken] = fencingToken.value
                    it[JobCheckpoints.schemaVersion] = schemaVersion
                    it[summaryValue] = request.nextValue
                    it[updatedAt] = now
                }
            }
        }
    }

    fun countRows(): Long = jdbc.transaction { withExposed { JobCheckpoints.selectAll().count() } }
}

class JobOutboxRepository(
    private val jdbc: JobSafetyJdbcExecutor,
) : JobSafetyExposedJdbcRepository<JobOutboxEntity, Long>(JobOutboxEntity::class.java) {
    internal fun enqueue(
        transaction: JobSafetyJdbcTransaction,
        request: JobRunRequest,
        now: Instant,
    ) {
        transaction.withExposed {
            JobOutboxEntries.insert {
                it[operationId] = request.operationId.value
                it[effectType] = "SUMMARY_PUBLISHED"
                it[status] = "PENDING"
                it[attemptCount] = 0
                it[nextAttemptAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    fun countRows(): Long = jdbc.transaction { withExposed { JobOutboxEntries.selectAll().count() } }
}

class JobEffectReceiptRepository :
    JobSafetyExposedJdbcRepository<JobEffectReceiptEntity, Long>(JobEffectReceiptEntity::class.java)

class JobSafetyRepositories(jdbc: JobSafetyJdbcExecutor) {
    val assignment = JobAssignmentRepository(jdbc)
    val rollout = JobRolloutMarkerRepository(jdbc)
    val resource = JobResourceRepository(jdbc)
    val execution = JobExecutionRepository(jdbc)
    val checkpoint = JobCheckpointRepository(jdbc)
    val outbox = JobOutboxRepository(jdbc)
    val effectReceipt = JobEffectReceiptRepository()
}
