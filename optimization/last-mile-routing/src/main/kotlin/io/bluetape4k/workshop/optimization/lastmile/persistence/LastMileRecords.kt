package io.bluetape4k.workshop.optimization.lastmile.persistence

import java.time.Instant

internal data class LastMileJobRecord(
    val id: Long = 0L,
    val jobId: String,
    val pickupCoordinateId: String,
    val deliveryCoordinateId: String,
    val demand: Int,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val deliveryWindowStart: Instant,
    val deliveryWindowEnd: Instant,
    val requiredSkill: String?,
    val priority: String,
    val status: String,
    val carrierVersion: Long,
)

internal data class LastMileVehicleRecord(
    val id: Long = 0L,
    val vehicleId: String,
    val driverId: String,
    val depotCoordinateId: String,
    val capacity: Int,
    val skills: String,
    val availableAt: Instant,
    val startedJobId: String?,
    val startedKind: String?,
    val startedCoordinateId: String?,
    val startedSequence: Int?,
    val startedAt: Instant?,
)

internal data class LastMileMatrixRevisionRecord(
    val id: Long = 0L,
    val revision: Long,
    val coordinateDigest: String,
    val createdAt: Instant,
)

internal data class LastMileMatrixEdgeRecord(
    val id: Long = 0L,
    val revision: Long,
    val fromCoordinateId: String,
    val toCoordinateId: String,
    val travelSeconds: Long,
)

internal data class LastMilePlanRecord(
    val id: Long = 0L,
    val planId: String,
    val planRevision: Long,
    val parentRevision: Long?,
    val requestGeneration: Long,
    val matrixRevision: Long,
    val providerRevision: Long?,
    val state: String,
    val hardScore: Long,
    val softScore: Long,
    val assignedJobs: Int,
    val unassignedJobs: Int,
)

internal data class LastMilePlanCarrierRecord(
    val id: Long = 0L,
    val planId: String,
    val planRevision: Long,
    val jobId: String,
    val carrierVersion: Long,
)

internal data class LastMilePlanStopRecord(
    val id: Long = 0L,
    val planId: String,
    val planRevision: Long,
    val vehicleId: String,
    val jobId: String,
    val kind: String,
    val coordinateId: String,
    val sequence: Int,
    val eta: Instant,
    val loadAfter: Int,
    val pinned: Boolean,
)

internal data class LastMileUnassignedRecord(
    val id: Long = 0L,
    val planId: String,
    val planRevision: Long,
    val jobId: String,
    val reason: String,
)

internal data class LastMileCommittedStopRecord(
    val id: Long = 0L,
    val jobId: String,
    val planId: String,
    val planRevision: Long,
    val vehicleId: String,
    val kind: String,
    val sequence: Int,
    val carrierVersion: Long,
    val committedAt: Instant,
)

internal data class LastMileEventRecord(
    val id: Long = 0L,
    val eventId: String,
    val aggregateId: String,
    val eventKey: String,
    val eventType: String,
    val occurredAt: Instant,
    val canonicalPayload: String,
    val digest: String,
)

internal data class LastMileCallbackInboxRecord(
    val id: Long = 0L,
    val provider: String,
    val eventId: String,
    val requestId: String,
    val providerRevision: Long,
    val payloadDigest: String,
    val status: String,
    val receivedAt: Instant,
)

internal data class LastMileOutboxRecord(
    val id: Long = 0L,
    val eventType: String,
    val payload: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val leaseOwner: String?,
    val leaseUntil: Instant?,
)

internal data class LastMileAuditRecord(
    val id: Long = 0L,
    val planId: String?,
    val planRevision: Long?,
    val decision: String,
    val redactedSummary: String,
)
