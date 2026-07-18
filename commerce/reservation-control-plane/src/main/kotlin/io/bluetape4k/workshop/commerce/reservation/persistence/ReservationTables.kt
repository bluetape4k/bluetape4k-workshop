package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import io.bluetape4k.workshop.commerce.reservation.domain.ResourceState
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import io.bluetape4k.workshop.commerce.reservation.idempotency.reservationHttpIdempotencyTable
import io.bluetape4k.workshop.commerce.reservation.notification.reservationNotificationDeliveryTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/** Capacity authority; `revision` and `occupiedCount` are updated by the same SQL CAS. */
internal object CapacityResourceTable : AuditableLongIdTable("reservation_capacity_resources") {
    val code = varchar("code", 80).uniqueIndex()
    val state = enumerationByName<ResourceState>("state", 16).default(ResourceState.OPEN)
    val capacity = integer("capacity")
    val occupiedCount = integer("occupied_count").default(0)
    val revision = long("revision").default(0)
    val policyVersion = long("policy_version")
    val timezone = varchar("timezone", 64).default("UTC")
}

/** Durable hold state indexed by expiry for bounded sweeper discovery. */
internal object ReservationHoldTable : AuditableLongIdTable("reservation_holds") {
    val resourceId = reference("resource_id", CapacityResourceTable, onDelete = ReferenceOption.RESTRICT).index()
    val ownerDigest = char("owner_digest", 64)
    val state = enumerationByName<HoldState>("state", 32)
    val revision = long("revision").default(0)
    val policyVersion = long("policy_version")
    val expiresAt = timestamp("expires_at")

    init {
        index(false, state, expiresAt, id)
    }
}

/** FIFO entries ordered by resource, state, sequence, and stable id. */
internal object WaitlistEntryTable : AuditableLongIdTable("reservation_waitlist_entries") {
    val resourceId = reference("resource_id", CapacityResourceTable, onDelete = ReferenceOption.RESTRICT)
    val ownerDigest = char("owner_digest", 64)
    val state = enumerationByName<WaitlistState>("state", 24)
    val sequence = long("sequence")
    val revision = long("revision").default(0)

    init {
        index(false, resourceId, state, sequence, id)
    }
}

/** One expiring offer per waitlist entry; the unique entry reference prevents double promotion. */
internal object ReservationOfferTable : AuditableLongIdTable("reservation_offers") {
    val resourceId = reference("resource_id", CapacityResourceTable, onDelete = ReferenceOption.RESTRICT).index()
    val entryId = reference("entry_id", WaitlistEntryTable, onDelete = ReferenceOption.RESTRICT).uniqueIndex()
    val ownerDigest = char("owner_digest", 64)
    val state = enumerationByName<OfferState>("state", 24)
    val revision = long("revision").default(0)
    val expiresAt = timestamp("expires_at")

    init {
        index(false, state, expiresAt, id)
    }
}

/** Append-only transition evidence keyed by aggregate identity and revision. */
internal object ReservationAuditTable : Table("reservation_transition_audits") {
    val aggregateType = varchar("aggregate_type", 32)
    val aggregateId = long("aggregate_id")
    val revision = long("revision")
    val outcome = varchar("outcome", 32)
    val reason = varchar("reason", 80).nullable()

    override val primaryKey = PrimaryKey(aggregateType, aggregateId, revision)
}

/** Complete application-owned schema used by bootstrap and isolated PostgreSQL fixtures. */
internal val reservationTables = arrayOf(
    CapacityResourceTable,
    ReservationHoldTable,
    WaitlistEntryTable,
    ReservationOfferTable,
    ReservationAuditTable,
    reservationHttpIdempotencyTable,
    reservationNotificationDeliveryTable,
)
