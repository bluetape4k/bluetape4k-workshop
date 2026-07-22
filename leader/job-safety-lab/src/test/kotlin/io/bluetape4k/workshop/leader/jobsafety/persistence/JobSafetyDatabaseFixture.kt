package io.bluetape4k.workshop.leader.jobsafety.persistence

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.EffectDeliveryState
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.MembershipRevision
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionId
import io.bluetape4k.workshop.leader.jobsafety.domain.TenantId
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.postgresql.ds.PGSimpleDataSource
import java.time.Instant

internal data class JobAuthoritySeed(
    val tenantId: TenantId,
    val membershipRevision: MembershipRevision,
    val regionId: RegionId,
    val regionEpoch: RegionEpoch,
    val namespaceEpoch: NamespaceEpoch,
    val minimumWriterVersion: ExecutionContractVersion,
    val checkpointSchemaVersion: Int,
)

internal class JobSafetyDatabaseFixture : AutoCloseable {
    val executor: JobSafetyJdbcExecutor

    init {
        val dataSource =
            PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username ?: PostgreSQLServer.USERNAME
                password = postgres.password ?: PostgreSQLServer.PASSWORD
            }
        executor = JobSafetyJdbcExecutor(dataSource, foregroundPermits = 1)
        executor.transaction {
            SchemaUtils.drop(*JOB_SAFETY_TABLES.reversedArray())
            SchemaUtils.createMissingTablesAndColumns(*JOB_SAFETY_TABLES)
        }
    }

    fun seedAuthority(seed: JobAuthoritySeed) {
        val authority = seed
        executor.transaction {
            JobAssignmentEntity.new {
                tenantId = authority.tenantId.value
                membershipRevision = authority.membershipRevision.value
                regionId = authority.regionId.value
                regionEpoch = authority.regionEpoch.value
                active = true
            }
            JobRolloutMarkerEntity.new {
                markerName = JobRolloutMarkerRepository.CURRENT_MARKER
                namespaceEpoch = authority.namespaceEpoch.value
                minimumWriterVersion = authority.minimumWriterVersion.value
                checkpointSchemaVersion = authority.checkpointSchemaVersion
            }
        }
    }

    fun seedResource(
        conflictKey: ConflictKey,
        namespaceEpoch: NamespaceEpoch,
        lastAcceptedFence: Long = 0L,
        summaryValue: Long = 0L,
    ) {
        val key = conflictKey
        val epoch = namespaceEpoch
        val now = Instant.now()
        executor.transaction {
            JobResourceEntity.new {
                this.conflictKey = key.value
                this.namespaceEpoch = epoch.value
                this.lastAcceptedFence = lastAcceptedFence
                this.summaryValue = summaryValue
                updatedAt = now
            }
        }
    }

    fun deactivateTenant(tenantId: TenantId, nextRevision: MembershipRevision) {
        executor.transaction {
            JobAssignments.update({ JobAssignments.tenantId eq tenantId.value }) {
                it[active] = false
                it[membershipRevision] = nextRevision.value
            }
        }
    }

    fun seedOutbox(operationId: OperationId, now: Instant = Instant.now()) {
        val effectOperationId = operationId
        val seedTime = now
        executor.transaction {
            JobOutboxEntity.new {
                this.operationId = effectOperationId.value
                effectType = "SUMMARY_PUBLISHED"
                status = EffectDeliveryState.PENDING.name
                attemptCount = 0
                nextAttemptAt = seedTime
                createdAt = seedTime
                updatedAt = seedTime
            }
        }
    }

    fun requeueOutbox(operationId: OperationId) {
        executor.transaction {
            JobOutboxEntries.update({ JobOutboxEntries.operationId eq operationId.value }) {
                it[status] = EffectDeliveryState.PENDING.name
                it[updatedAt] = Instant.now()
            }
        }
    }

    override fun close() {
        executor.transaction {
            SchemaUtils.drop(*JOB_SAFETY_TABLES.reversedArray())
        }
    }

    companion object {
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }
}
