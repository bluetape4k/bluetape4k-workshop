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

/** capacity authority입니다. `revision`과 `occupiedCount`는 같은 SQL CAS로 update됩니다. */
internal object CapacityResourceTable : AuditableLongIdTable("reservation_capacity_resources") {
    val code = varchar("code", 80).uniqueIndex()
    val state = enumerationByName<ResourceState>("state", 16).default(ResourceState.OPEN)
    val capacity = integer("capacity")
    val occupiedCount = integer("occupied_count").default(0)
    val revision = long("revision").default(0)
    val policyVersion = long("policy_version")
    val timezone = varchar("timezone", 64).default("UTC")
}

/** bounded sweeper discovery를 위해 expiry로 index된 durable hold state입니다. */
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

/** resource, state, sequence, stable id로 정렬되는 FIFO entry입니다. */
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

/** waitlist entry당 하나의 expiring offer입니다. unique entry reference가 double promotion을 막습니다. */
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

/** aggregate identity와 revision으로 keying되는 append-only transition evidence입니다. */
internal object ReservationAuditTable : Table("reservation_transition_audits") {
    val aggregateType = varchar("aggregate_type", 32)
    val aggregateId = long("aggregate_id")
    val revision = long("revision")
    val outcome = varchar("outcome", 32)
    val reason = varchar("reason", 80).nullable()

    override val primaryKey = PrimaryKey(aggregateType, aggregateId, revision)
}

/** bootstrap과 isolated PostgreSQL fixture에서 사용하는 완전한 application-owned schema입니다. */
internal val reservationTables =
    arrayOf(
        CapacityResourceTable,
        ReservationHoldTable,
        WaitlistEntryTable,
        ReservationOfferTable,
        ReservationAuditTable,
        reservationHttpIdempotencyTable,
        reservationNotificationDeliveryTable
    )
