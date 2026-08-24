package io.bluetape4k.workshop.optimization.lastmile.persistence

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

@Repository
internal class LastMilePlanCarrierRepository : LongJdbcRepository<LastMilePlanCarrierRecord> {
    override val table = LastMilePlanCarriersTable

    override fun extractId(entity: LastMilePlanCarrierRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMilePlanCarrierRecord = LastMilePlanCarrierRecord(
        id = this[LastMilePlanCarriersTable.id].value,
        planId = this[LastMilePlanCarriersTable.planId],
        planRevision = this[LastMilePlanCarriersTable.planRevision],
        jobId = this[LastMilePlanCarriersTable.jobId],
        carrierVersion = this[LastMilePlanCarriersTable.carrierVersion],
    )

    fun save(record: LastMilePlanCarrierRecord): LastMilePlanCarrierRecord {
        val id = LastMilePlanCarriersTable.insertAndGetId {
            it[planId] = record.planId
            it[planRevision] = record.planRevision
            it[jobId] = record.jobId
            it[carrierVersion] = record.carrierVersion
        }
        return findById(id.value)
    }

    fun findAllByPlan(planId: String, planRevision: Long): List<LastMilePlanCarrierRecord> =
        LastMilePlanCarriersTable.selectAll()
            .where {
                (LastMilePlanCarriersTable.planId eq planId) and
                    (LastMilePlanCarriersTable.planRevision eq planRevision)
            }
            .map { row -> with(this) { row.toEntity() } }
}

@Repository
internal class LastMilePlanStopRepository : LongJdbcRepository<LastMilePlanStopRecord> {
    override val table = LastMilePlanStopsTable

    override fun extractId(entity: LastMilePlanStopRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMilePlanStopRecord = LastMilePlanStopRecord(
        id = this[LastMilePlanStopsTable.id].value,
        planId = this[LastMilePlanStopsTable.planId],
        planRevision = this[LastMilePlanStopsTable.planRevision],
        vehicleId = this[LastMilePlanStopsTable.vehicleId],
        jobId = this[LastMilePlanStopsTable.jobId],
        kind = this[LastMilePlanStopsTable.kind],
        coordinateId = this[LastMilePlanStopsTable.coordinateId],
        sequence = this[LastMilePlanStopsTable.sequence],
        eta = this[LastMilePlanStopsTable.eta],
        loadAfter = this[LastMilePlanStopsTable.loadAfter],
        pinned = this[LastMilePlanStopsTable.pinned],
    )

    fun save(record: LastMilePlanStopRecord): LastMilePlanStopRecord {
        val id = LastMilePlanStopsTable.insertAndGetId {
            it[planId] = record.planId
            it[planRevision] = record.planRevision
            it[vehicleId] = record.vehicleId
            it[jobId] = record.jobId
            it[kind] = record.kind
            it[coordinateId] = record.coordinateId
            it[sequence] = record.sequence
            it[eta] = record.eta
        it[loadAfter] = record.loadAfter
        it[pinned] = record.pinned
            it[pinned] = record.pinned
        }
        return findById(id.value)
    }

    fun findAllByPlan(planId: String, planRevision: Long): List<LastMilePlanStopRecord> =
        LastMilePlanStopsTable.selectAll()
            .where {
                (LastMilePlanStopsTable.planId eq planId) and
                    (LastMilePlanStopsTable.planRevision eq planRevision)
            }
            .map { row -> with(this) { row.toEntity() } }

    fun findPlanRevisionsByVehicle(vehicleId: String): List<Pair<String, Long>> =
        LastMilePlanStopsTable.selectAll()
            .where { LastMilePlanStopsTable.vehicleId eq vehicleId }
            .map { row -> row[LastMilePlanStopsTable.planId] to row[LastMilePlanStopsTable.planRevision] }
            .distinct()
}

@Repository
internal class LastMileUnassignedRepository : LongJdbcRepository<LastMileUnassignedRecord> {
    override val table = LastMileUnassignedTable

    override fun extractId(entity: LastMileUnassignedRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileUnassignedRecord = LastMileUnassignedRecord(
        id = this[LastMileUnassignedTable.id].value,
        planId = this[LastMileUnassignedTable.planId],
        planRevision = this[LastMileUnassignedTable.planRevision],
        jobId = this[LastMileUnassignedTable.jobId],
        reason = this[LastMileUnassignedTable.reason],
    )

    fun save(record: LastMileUnassignedRecord): LastMileUnassignedRecord {
        val id = LastMileUnassignedTable.insertAndGetId {
            it[planId] = record.planId
            it[planRevision] = record.planRevision
            it[jobId] = record.jobId
            it[reason] = record.reason
        }
        return findById(id.value)
    }

    fun findAllByPlan(planId: String, planRevision: Long): List<LastMileUnassignedRecord> =
        LastMileUnassignedTable.selectAll()
            .where {
                (LastMileUnassignedTable.planId eq planId) and
                    (LastMileUnassignedTable.planRevision eq planRevision)
            }
            .map { row -> with(this) { row.toEntity() } }
}
