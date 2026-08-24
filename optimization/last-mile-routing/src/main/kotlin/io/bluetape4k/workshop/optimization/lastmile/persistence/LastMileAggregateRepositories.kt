package io.bluetape4k.workshop.optimization.lastmile.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

@Repository
internal class LastMileJobRepository : LongAuditableJdbcRepository<LastMileJobRecord, LastMileJobsTable> {
    override val table = LastMileJobsTable

    override fun extractId(entity: LastMileJobRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileJobRecord = LastMileJobRecord(
        id = this[LastMileJobsTable.id].value,
        jobId = this[LastMileJobsTable.jobId],
        pickupCoordinateId = this[LastMileJobsTable.pickupCoordinateId],
        deliveryCoordinateId = this[LastMileJobsTable.deliveryCoordinateId],
        demand = this[LastMileJobsTable.demand],
        pickupWindowStart = this[LastMileJobsTable.pickupWindowStart],
        pickupWindowEnd = this[LastMileJobsTable.pickupWindowEnd],
        deliveryWindowStart = this[LastMileJobsTable.deliveryWindowStart],
        deliveryWindowEnd = this[LastMileJobsTable.deliveryWindowEnd],
        requiredSkill = this[LastMileJobsTable.requiredSkill],
        priority = this[LastMileJobsTable.priority],
        status = this[LastMileJobsTable.status],
        carrierVersion = this[LastMileJobsTable.carrierVersion],
    )

    fun save(record: LastMileJobRecord): LastMileJobRecord {
        val id = LastMileJobsTable.insertAndGetId {
            it[jobId] = record.jobId
            it[pickupCoordinateId] = record.pickupCoordinateId
            it[deliveryCoordinateId] = record.deliveryCoordinateId
            it[demand] = record.demand
            it[pickupWindowStart] = record.pickupWindowStart
            it[pickupWindowEnd] = record.pickupWindowEnd
            it[deliveryWindowStart] = record.deliveryWindowStart
            it[deliveryWindowEnd] = record.deliveryWindowEnd
            it[requiredSkill] = record.requiredSkill
            it[priority] = record.priority
            it[status] = record.status
            it[carrierVersion] = record.carrierVersion
        }
        return findById(id.value)
    }

    fun findByJobId(jobId: String): LastMileJobRecord? = LastMileJobsTable
        .selectAll()
        .where { LastMileJobsTable.jobId eq jobId }
        .singleOrNull()
        ?.let { row -> with(this) { row.toEntity() } }

    fun updateIfCarrierVersion(jobId: String, expectedVersion: Long, nextStatus: String): Boolean =
        auditedUpdateAll(
            predicate = {
                (LastMileJobsTable.jobId eq jobId) and
                    (LastMileJobsTable.carrierVersion eq expectedVersion)
            },
        ) {
            it[LastMileJobsTable.status] = nextStatus
            it[LastMileJobsTable.carrierVersion] = expectedVersion + 1L
        } == 1
}

@Repository
internal class LastMileVehicleRepository : LongAuditableJdbcRepository<LastMileVehicleRecord, LastMileVehiclesTable> {
    override val table = LastMileVehiclesTable

    override fun extractId(entity: LastMileVehicleRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileVehicleRecord = LastMileVehicleRecord(
        id = this[LastMileVehiclesTable.id].value,
        vehicleId = this[LastMileVehiclesTable.vehicleId],
        driverId = this[LastMileVehiclesTable.driverId],
        depotCoordinateId = this[LastMileVehiclesTable.depotCoordinateId],
        capacity = this[LastMileVehiclesTable.capacity],
        skills = this[LastMileVehiclesTable.skills],
        availableAt = this[LastMileVehiclesTable.availableAt],
        startedJobId = this[LastMileVehiclesTable.startedJobId],
        startedKind = this[LastMileVehiclesTable.startedKind],
        startedCoordinateId = this[LastMileVehiclesTable.startedCoordinateId],
        startedSequence = this[LastMileVehiclesTable.startedSequence],
        startedAt = this[LastMileVehiclesTable.startedAt],
    )

    fun save(record: LastMileVehicleRecord): LastMileVehicleRecord {
        val id = LastMileVehiclesTable.insertAndGetId {
            it[vehicleId] = record.vehicleId
            it[driverId] = record.driverId
            it[depotCoordinateId] = record.depotCoordinateId
            it[capacity] = record.capacity
            it[skills] = record.skills
            it[availableAt] = record.availableAt
            it[startedJobId] = record.startedJobId
            it[startedKind] = record.startedKind
            it[startedCoordinateId] = record.startedCoordinateId
            it[startedSequence] = record.startedSequence
            it[startedAt] = record.startedAt
        }
        return findById(id.value)
    }

    fun findByVehicleId(vehicleId: String): LastMileVehicleRecord? = LastMileVehiclesTable
        .selectAll()
        .where { LastMileVehiclesTable.vehicleId eq vehicleId }
        .singleOrNull()
        ?.let { row -> with(this) { row.toEntity() } }
}

@Repository
internal class LastMileMatrixRevisionRepository : LongJdbcRepository<LastMileMatrixRevisionRecord> {
    override val table = LastMileMatrixRevisionsTable

    override fun extractId(entity: LastMileMatrixRevisionRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileMatrixRevisionRecord = LastMileMatrixRevisionRecord(
        id = this[LastMileMatrixRevisionsTable.id].value,
        revision = this[LastMileMatrixRevisionsTable.revision],
        coordinateDigest = this[LastMileMatrixRevisionsTable.coordinateDigest],
        createdAt = this[LastMileMatrixRevisionsTable.createdAt],
    )

    fun save(record: LastMileMatrixRevisionRecord): LastMileMatrixRevisionRecord {
        val id = LastMileMatrixRevisionsTable.insertAndGetId {
            it[revision] = record.revision
            it[coordinateDigest] = record.coordinateDigest
            it[createdAt] = record.createdAt
        }
        return findById(id.value)
    }
}

@Repository
internal class LastMileMatrixEdgeRepository : LongJdbcRepository<LastMileMatrixEdgeRecord> {
    override val table = LastMileMatrixEdgesTable

    override fun extractId(entity: LastMileMatrixEdgeRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMileMatrixEdgeRecord = LastMileMatrixEdgeRecord(
        id = this[LastMileMatrixEdgesTable.id].value,
        revision = this[LastMileMatrixEdgesTable.revision],
        fromCoordinateId = this[LastMileMatrixEdgesTable.fromCoordinateId],
        toCoordinateId = this[LastMileMatrixEdgesTable.toCoordinateId],
        travelSeconds = this[LastMileMatrixEdgesTable.travelSeconds],
    )

    fun save(record: LastMileMatrixEdgeRecord): LastMileMatrixEdgeRecord {
        val id = LastMileMatrixEdgesTable.insertAndGetId {
            it[revision] = record.revision
            it[fromCoordinateId] = record.fromCoordinateId
            it[toCoordinateId] = record.toCoordinateId
            it[travelSeconds] = record.travelSeconds
        }
        return findById(id.value)
    }
}

@Repository
internal class LastMilePlanRepository : LongAuditableJdbcRepository<LastMilePlanRecord, LastMilePlansTable> {
    override val table = LastMilePlansTable

    override fun extractId(entity: LastMilePlanRecord): Long = entity.id

    override fun ResultRow.toEntity(): LastMilePlanRecord = LastMilePlanRecord(
        id = this[LastMilePlansTable.id].value,
        planId = this[LastMilePlansTable.planId],
        planRevision = this[LastMilePlansTable.planRevision],
        parentRevision = this[LastMilePlansTable.parentRevision],
        requestGeneration = this[LastMilePlansTable.requestGeneration],
        matrixRevision = this[LastMilePlansTable.matrixRevision],
        providerRevision = this[LastMilePlansTable.providerRevision],
        state = this[LastMilePlansTable.state],
        hardScore = this[LastMilePlansTable.hardScore],
        softScore = this[LastMilePlansTable.softScore],
        assignedJobs = this[LastMilePlansTable.assignedJobs],
        unassignedJobs = this[LastMilePlansTable.unassignedJobs],
    )

    fun save(record: LastMilePlanRecord): LastMilePlanRecord {
        val id = LastMilePlansTable.insertAndGetId {
            it[planId] = record.planId
            it[planRevision] = record.planRevision
            it[parentRevision] = record.parentRevision
            it[requestGeneration] = record.requestGeneration
            it[matrixRevision] = record.matrixRevision
            it[providerRevision] = record.providerRevision
            it[state] = record.state
            it[hardScore] = record.hardScore
            it[softScore] = record.softScore
            it[assignedJobs] = record.assignedJobs
            it[unassignedJobs] = record.unassignedJobs
        }
        return findById(id.value)
    }

    fun findByPlan(planId: String, planRevision: Long): LastMilePlanRecord? = LastMilePlansTable
        .selectAll()
        .where {
            (LastMilePlansTable.planId eq planId) and
                (LastMilePlansTable.planRevision eq planRevision)
        }
        .singleOrNull()
        ?.let { row -> with(this) { row.toEntity() } }

    fun findLatestByPlan(planId: String): LastMilePlanRecord? = LastMilePlansTable
        .selectAll()
        .where { LastMilePlansTable.planId eq planId }
        .orderBy(LastMilePlansTable.planRevision to SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.let { row -> with(this) { row.toEntity() } }

    fun approveIfState(planId: String, planRevision: Long, expectedState: String): Boolean =
        auditedUpdateAll(
            predicate = {
                (LastMilePlansTable.planId eq planId) and
                    (LastMilePlansTable.planRevision eq planRevision) and
                    (LastMilePlansTable.state eq expectedState)
            },
        ) {
            it[LastMilePlansTable.state] = "APPROVED"
        } == 1

    fun commitIfApproved(planId: String, planRevision: Long): Boolean =
        auditedUpdateAll(
            predicate = {
                (LastMilePlansTable.planId eq planId) and
                    (LastMilePlansTable.planRevision eq planRevision) and
                    (LastMilePlansTable.state eq "APPROVED")
            },
        ) {
            it[LastMilePlansTable.state] = "COMMITTED"
        } == 1

    fun updateProviderRevisionIfGreater(
        planId: String,
        planRevision: Long,
        expectedRevision: Long,
        nextRevision: Long,
    ): Boolean = auditedUpdateAll(
        predicate = {
            (LastMilePlansTable.planId eq planId) and
                (LastMilePlansTable.planRevision eq planRevision) and
                (LastMilePlansTable.providerRevision eq expectedRevision)
        },
    ) {
        it[LastMilePlansTable.providerRevision] = nextRevision
    } == 1

    fun updateProviderRevisionIfAbsent(
        planId: String,
        planRevision: Long,
        nextRevision: Long,
    ): Boolean = auditedUpdateAll(
        predicate = {
            (LastMilePlansTable.planId eq planId) and
                (LastMilePlansTable.planRevision eq planRevision) and
                LastMilePlansTable.providerRevision.isNull()
        },
    ) {
        it[LastMilePlansTable.providerRevision] = nextRevision
    } == 1
}
