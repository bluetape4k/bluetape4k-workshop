package io.bluetape4k.workshop.leader.jobsafety.persistence

import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.EffectDeliveryState
import io.bluetape4k.workshop.leader.jobsafety.domain.ExternalEffectResult
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRunRequest
import io.bluetape4k.workshop.leader.jobsafety.domain.MembershipRevision
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionId
import io.bluetape4k.workshop.leader.jobsafety.domain.TenantId
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.Duration

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

data class JobOutboxRecord(
    val operationId: OperationId,
    val state: EffectDeliveryState,
    val attemptCount: Int,
)

data class JobEffectReceiptRecord(
    val provider: String,
    val operationId: OperationId,
    val result: ExternalEffectResult,
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
                    val persistedFenceEpoch = row[JobResources.lastAcceptedFenceEpoch]
                    val persistedFence = row[JobResources.lastAcceptedFence]
                    JobResourceSnapshot(
                        conflictKey = ConflictKey.of(row[JobResources.conflictKey]),
                        namespaceEpoch = NamespaceEpoch(row[JobResources.namespaceEpoch]),
                        lastAcceptedFence = persistedFence.takeIf { it > 0L }?.let { FencingToken(persistedFenceEpoch, it) },
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
                    (
                        (JobResources.lastAcceptedFenceEpoch less fencingToken.epoch) or
                            ((JobResources.lastAcceptedFenceEpoch eq fencingToken.epoch) and
                                (JobResources.lastAcceptedFence less fencingToken.sequence))
                    )
            }) {
                it[lastAcceptedFenceEpoch] = fencingToken.epoch
                it[lastAcceptedFence] = fencingToken.sequence
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
                it[JobExecutions.fencingTokenEpoch] = fencingToken.epoch
                it[JobExecutions.fencingToken] = fencingToken.sequence
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
                    it[JobCheckpoints.fencingTokenEpoch] = fencingToken.epoch
                    it[JobCheckpoints.fencingToken] = fencingToken.sequence
                    it[JobCheckpoints.schemaVersion] = schemaVersion
                    it[summaryValue] = request.nextValue
                    it[updatedAt] = now
                }
            if (updated == 0) {
                JobCheckpoints.insert {
                    it[conflictKey] = request.conflictKey.value
                    it[JobCheckpoints.fencingTokenEpoch] = fencingToken.epoch
                    it[JobCheckpoints.fencingToken] = fencingToken.sequence
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

    fun find(operationId: OperationId): JobOutboxRecord? =
        jdbc.transaction {
            withExposed {
                JobOutboxEntries.selectAll()
                    .where { JobOutboxEntries.operationId eq operationId.value }
                    .singleOrNull()
                    ?.toOutboxRecord()
            }
        }

    fun claimNext(
        state: EffectDeliveryState,
        now: Instant = Instant.now(),
        claimTimeout: Duration = DEFAULT_CLAIM_TIMEOUT,
    ): JobOutboxRecord? =
        jdbc.transaction {
            withExposed {
                val row =
                    JobOutboxEntries.selectAll()
                        .where {
                            (JobOutboxEntries.status eq state.name) and
                                (JobOutboxEntries.nextAttemptAt lessEq now)
                        }
                        .orderBy(JobOutboxEntries.id to SortOrder.ASC)
                        .limit(1)
                        .forUpdate()
                        .singleOrNull()
                        ?: return@withExposed null
                val id = row[JobOutboxEntries.id]
                val nextAttempt = row[JobOutboxEntries.attemptCount] + 1
                JobOutboxEntries.update({ JobOutboxEntries.id eq id }) {
                    it[status] = EffectDeliveryState.CLAIMED.name
                    it[attemptCount] = nextAttempt
                    it[nextAttemptAt] = now.plus(claimTimeout)
                    it[updatedAt] = now
                }
                JobOutboxRecord(
                    operationId = OperationId(row[JobOutboxEntries.operationId]),
                    state = EffectDeliveryState.CLAIMED,
                    attemptCount = nextAttempt,
                )
            }
        }

    fun claimNextForReconciliation(
        now: Instant = Instant.now(),
        claimTimeout: Duration = DEFAULT_CLAIM_TIMEOUT,
    ): JobOutboxRecord? =
        jdbc.transaction {
            withExposed {
                val row =
                    JobOutboxEntries.selectAll()
                        .where {
                            ((JobOutboxEntries.status eq EffectDeliveryState.RECONCILIATION_REQUIRED.name) and
                                (JobOutboxEntries.nextAttemptAt lessEq now)) or
                                ((JobOutboxEntries.status eq EffectDeliveryState.CLAIMED.name) and
                                    (JobOutboxEntries.nextAttemptAt lessEq now))
                        }
                        .orderBy(JobOutboxEntries.id to SortOrder.ASC)
                        .limit(1)
                        .forUpdate()
                        .singleOrNull()
                        ?: return@withExposed null
                val id = row[JobOutboxEntries.id]
                val nextAttempt = row[JobOutboxEntries.attemptCount] + 1
                JobOutboxEntries.update({ JobOutboxEntries.id eq id }) {
                    it[status] = EffectDeliveryState.CLAIMED.name
                    it[attemptCount] = nextAttempt
                    it[nextAttemptAt] = now.plus(claimTimeout)
                    it[updatedAt] = now
                }
                JobOutboxRecord(
                    operationId = OperationId(row[JobOutboxEntries.operationId]),
                    state = EffectDeliveryState.CLAIMED,
                    attemptCount = nextAttempt,
                )
            }
        }

    internal fun complete(
        transaction: JobSafetyJdbcTransaction,
        operationId: OperationId,
        state: EffectDeliveryState,
    ) {
        transaction.withExposed {
            val now = Instant.now()
            check(
                JobOutboxEntries.update({ JobOutboxEntries.operationId eq operationId.value }) {
                    it[status] = state.name
                    it[nextAttemptAt] = now
                    it[updatedAt] = now
                } == 1,
            ) { "outbox operation not found" }
        }
    }

    internal fun <T> transaction(block: JobSafetyJdbcTransaction.() -> T): T = jdbc.transaction(block)

    private fun org.jetbrains.exposed.v1.core.ResultRow.toOutboxRecord(): JobOutboxRecord =
        JobOutboxRecord(
            operationId = OperationId(this[JobOutboxEntries.operationId]),
            state = EffectDeliveryState.valueOf(this[JobOutboxEntries.status]),
            attemptCount = this[JobOutboxEntries.attemptCount],
        )

    private companion object {
        val DEFAULT_CLAIM_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

class JobEffectReceiptRepository(
    private val jdbc: JobSafetyJdbcExecutor,
) : JobSafetyExposedJdbcRepository<JobEffectReceiptEntity, Long>(JobEffectReceiptEntity::class.java) {
    fun find(provider: String, operationId: OperationId): JobEffectReceiptRecord? =
        jdbc.transaction {
            withExposed {
                JobEffectReceipts.selectAll()
                    .where {
                        (JobEffectReceipts.provider eq provider) and
                            (JobEffectReceipts.operationId eq operationId.value)
                    }
                    .singleOrNull()
                    ?.let { row ->
                        JobEffectReceiptRecord(
                            provider = row[JobEffectReceipts.provider],
                            operationId = OperationId(row[JobEffectReceipts.operationId]),
                            result = ExternalEffectResult.valueOf(row[JobEffectReceipts.status]),
                        )
                    }
            }
        }

    fun count(provider: String, operationId: OperationId): Long =
        jdbc.transaction {
            withExposed {
                JobEffectReceipts.selectAll()
                    .where {
                        (JobEffectReceipts.provider eq provider) and
                            (JobEffectReceipts.operationId eq operationId.value)
                    }
                    .count()
            }
        }

    internal fun record(
        transaction: JobSafetyJdbcTransaction,
        provider: String,
        operationId: OperationId,
        result: ExternalEffectResult,
    ) {
        transaction.withExposed {
            val exists =
                JobEffectReceipts.selectAll()
                    .where {
                        (JobEffectReceipts.provider eq provider) and
                            (JobEffectReceipts.operationId eq operationId.value)
                    }
                    .limit(1)
                    .any()
            if (!exists) {
                JobEffectReceipts.insert {
                    it[JobEffectReceipts.provider] = provider
                    it[JobEffectReceipts.operationId] = operationId.value
                    it[status] = result.name
                    it[createdAt] = Instant.now()
                }
            }
        }
    }
}

class JobSafetyRepositories(jdbc: JobSafetyJdbcExecutor) {
    val assignment = JobAssignmentRepository(jdbc)
    val rollout = JobRolloutMarkerRepository(jdbc)
    val resource = JobResourceRepository(jdbc)
    val execution = JobExecutionRepository(jdbc)
    val checkpoint = JobCheckpointRepository(jdbc)
    val outbox = JobOutboxRepository(jdbc)
    val effectReceipt = JobEffectReceiptRepository(jdbc)
}
