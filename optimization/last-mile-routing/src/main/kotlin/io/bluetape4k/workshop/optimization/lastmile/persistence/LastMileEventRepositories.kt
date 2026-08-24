package io.bluetape4k.workshop.optimization.lastmile.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

@Repository
internal class LastMileCommittedStopRepository : LongJdbcRepository<LastMileCommittedStopRecord> {
    override val table = LastMileCommittedStopsTable

    override fun extractId(entity: LastMileCommittedStopRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileCommittedStopRecord = LastMileCommittedStopRecord(
        id = this[LastMileCommittedStopsTable.id].value,
        jobId = this[LastMileCommittedStopsTable.jobId],
        planId = this[LastMileCommittedStopsTable.planId],
        planRevision = this[LastMileCommittedStopsTable.planRevision],
        vehicleId = this[LastMileCommittedStopsTable.vehicleId],
        kind = this[LastMileCommittedStopsTable.kind],
        sequence = this[LastMileCommittedStopsTable.sequence],
        carrierVersion = this[LastMileCommittedStopsTable.carrierVersion],
        committedAt = this[LastMileCommittedStopsTable.committedAt],
    )

    fun save(record: LastMileCommittedStopRecord): LastMileCommittedStopRecord {
        val id = LastMileCommittedStopsTable.insertAndGetId {
            it[jobId] = record.jobId
            it[planId] = record.planId
            it[planRevision] = record.planRevision
            it[vehicleId] = record.vehicleId
            it[kind] = record.kind
            it[sequence] = record.sequence
            it[carrierVersion] = record.carrierVersion
            it[committedAt] = record.committedAt
        }
        return findById(id.value)
    }
}

@Repository
internal class LastMileEventRepository : LongAuditableJdbcRepository<LastMileEventRecord, LastMileEventsTable> {
    override val table = LastMileEventsTable

    override fun extractId(entity: LastMileEventRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileEventRecord = LastMileEventRecord(
        id = this[LastMileEventsTable.id].value,
        eventId = this[LastMileEventsTable.eventId],
        aggregateId = this[LastMileEventsTable.aggregateId],
        eventKey = this[LastMileEventsTable.eventKey],
        eventType = this[LastMileEventsTable.eventType],
        occurredAt = this[LastMileEventsTable.occurredAt],
        canonicalPayload = this[LastMileEventsTable.canonicalPayload],
        digest = this[LastMileEventsTable.digest],
    )

    fun appendIfAbsent(record: LastMileEventRecord): Boolean = LastMileEventsTable.insertIgnore {
        it[eventId] = record.eventId
        it[aggregateId] = record.aggregateId
        it[eventKey] = record.eventKey
        it[eventType] = record.eventType
        it[occurredAt] = record.occurredAt
        it[canonicalPayload] = record.canonicalPayload
        it[digest] = record.digest
    }.insertedCount > 0

    fun findByAggregateAndKey(aggregateId: String, eventKey: String): LastMileEventRecord? =
        LastMileEventsTable.selectAll()
            .where {
                (LastMileEventsTable.aggregateId eq aggregateId) and
                    (LastMileEventsTable.eventKey eq eventKey)
            }
            .singleOrNull()
            ?.let { row -> with(this) { row.toEntity() } }
}

@Repository
internal class LastMileCallbackInboxRepository : LongJdbcRepository<LastMileCallbackInboxRecord> {
    override val table = LastMileCallbackInboxTable

    override fun extractId(entity: LastMileCallbackInboxRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileCallbackInboxRecord = LastMileCallbackInboxRecord(
        id = this[LastMileCallbackInboxTable.id].value,
        provider = this[LastMileCallbackInboxTable.provider],
        eventId = this[LastMileCallbackInboxTable.eventId],
        requestId = this[LastMileCallbackInboxTable.requestId],
        providerRevision = this[LastMileCallbackInboxTable.providerRevision],
        payloadDigest = this[LastMileCallbackInboxTable.payloadDigest],
        status = this[LastMileCallbackInboxTable.status],
        receivedAt = this[LastMileCallbackInboxTable.receivedAt],
    )

    fun insertIfAbsent(record: LastMileCallbackInboxRecord): Boolean = LastMileCallbackInboxTable.insertIgnore {
        it[provider] = record.provider
        it[eventId] = record.eventId
        it[requestId] = record.requestId
        it[providerRevision] = record.providerRevision
        it[payloadDigest] = record.payloadDigest
        it[status] = record.status
        it[receivedAt] = record.receivedAt
    }.insertedCount > 0

    fun find(provider: String, eventId: String): LastMileCallbackInboxRecord? = LastMileCallbackInboxTable
        .selectAll()
        .where {
            (LastMileCallbackInboxTable.provider eq provider) and
                (LastMileCallbackInboxTable.eventId eq eventId)
        }
        .singleOrNull()
        ?.let { row -> with(this) { row.toEntity() } }

    fun updateStatus(provider: String, eventId: String, status: String): Boolean =
        LastMileCallbackInboxTable.update({
            (LastMileCallbackInboxTable.provider eq provider) and
                (LastMileCallbackInboxTable.eventId eq eventId)
        }) {
            it[LastMileCallbackInboxTable.status] = status
        } == 1
}

@Repository
internal class LastMileOutboxRepository : LongAuditableJdbcRepository<LastMileOutboxRecord, LastMileOutboxTable> {
    override val table = LastMileOutboxTable

    override fun extractId(entity: LastMileOutboxRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileOutboxRecord = LastMileOutboxRecord(
        id = this[LastMileOutboxTable.id].value,
        eventType = this[LastMileOutboxTable.eventType],
        payload = this[LastMileOutboxTable.payload],
        status = this[LastMileOutboxTable.status],
        attempts = this[LastMileOutboxTable.attempts],
        nextAttemptAt = this[LastMileOutboxTable.nextAttemptAt],
        leaseOwner = this[LastMileOutboxTable.leaseOwner],
        leaseUntil = this[LastMileOutboxTable.leaseUntil],
    )

    fun save(record: LastMileOutboxRecord): LastMileOutboxRecord {
        val id = LastMileOutboxTable.insertAndGetId {
            it[eventType] = record.eventType
            it[payload] = record.payload
            it[status] = record.status
            it[attempts] = record.attempts
            it[nextAttemptAt] = record.nextAttemptAt
            it[leaseOwner] = record.leaseOwner
            it[leaseUntil] = record.leaseUntil
        }
        return findById(id.value)
    }

    fun claimNext(workerId: String, now: Instant, leaseDuration: Duration): LastMileOutboxRecord? {
        val candidate = findAll {
            (LastMileOutboxTable.nextAttemptAt lessEq now) and
                (
                    (LastMileOutboxTable.status eq "PENDING") or
                        ((LastMileOutboxTable.status eq "CLAIMED") and
                            (LastMileOutboxTable.leaseUntil less now))
                ) and
                (LastMileOutboxTable.leaseUntil.isNull() or (LastMileOutboxTable.leaseUntil less now))
        }.firstOrNull() ?: return null
        val claimedUntil = now.plus(leaseDuration)
        val updated = auditedUpdateAll(
            predicate = {
                (LastMileOutboxTable.id eq candidate.id) and
                    (LastMileOutboxTable.nextAttemptAt lessEq now) and
                    (
                        LastMileOutboxTable.leaseUntil.isNull() or
                            (LastMileOutboxTable.leaseUntil less now)
                    )
            },
        ) {
            it[LastMileOutboxTable.status] = "CLAIMED"
            it[LastMileOutboxTable.leaseOwner] = workerId
            it[LastMileOutboxTable.leaseUntil] = claimedUntil
        }
        return if (updated == 1) findById(candidate.id) else null
    }

    fun markCompleted(id: Long, workerId: String): Boolean = auditedUpdateAll(
        predicate = {
            (LastMileOutboxTable.id eq id) and
                (LastMileOutboxTable.status eq "CLAIMED") and
                (LastMileOutboxTable.leaseOwner eq workerId)
        },
    ) {
        it[LastMileOutboxTable.status] = "COMPLETED"
        it[LastMileOutboxTable.leaseOwner] = null
        it[LastMileOutboxTable.leaseUntil] = null
    } == 1

    fun markFailure(
        id: Long,
        workerId: String,
        now: Instant,
        retryDelay: Duration,
        maxAttempts: Int,
    ): Boolean {
        val current = findById(id)
        val nextAttempts = current.attempts + 1
        return auditedUpdateAll(
            predicate = {
                (LastMileOutboxTable.id eq id) and
                    (LastMileOutboxTable.status eq "CLAIMED") and
                    (LastMileOutboxTable.leaseOwner eq workerId)
            },
        ) {
            it[LastMileOutboxTable.attempts] = nextAttempts
            it[LastMileOutboxTable.status] = if (nextAttempts >= maxAttempts) "DEAD_LETTER" else "PENDING"
            it[LastMileOutboxTable.nextAttemptAt] = now.plus(retryDelay)
            it[LastMileOutboxTable.leaseOwner] = null
            it[LastMileOutboxTable.leaseUntil] = null
        } == 1
    }
}

@Repository
internal class LastMileAuditRepository : LongAuditableJdbcRepository<LastMileAuditRecord, LastMileAuditsTable> {
    override val table = LastMileAuditsTable

    override fun extractId(entity: LastMileAuditRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileAuditRecord = LastMileAuditRecord(
        id = this[LastMileAuditsTable.id].value,
        planId = this[LastMileAuditsTable.planId],
        planRevision = this[LastMileAuditsTable.planRevision],
        decision = this[LastMileAuditsTable.decision],
        redactedSummary = this[LastMileAuditsTable.redactedSummary],
    )

    fun append(record: LastMileAuditRecord): LastMileAuditRecord {
        val id = LastMileAuditsTable.insertAndGetId {
            it[planId] = record.planId
            it[planRevision] = record.planRevision
            it[decision] = record.decision
            it[redactedSummary] = record.redactedSummary
        }
        return findById(id.value)
    }
}
