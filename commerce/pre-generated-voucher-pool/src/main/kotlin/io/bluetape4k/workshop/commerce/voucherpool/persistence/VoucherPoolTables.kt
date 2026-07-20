@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

internal object VoucherPoolCampaignTable : Table("voucher_pool_campaigns") {
    val tenantId = varchar("tenant_id", 64)
    val campaignId = javaUUID("campaign_id")
    val state = enumerationByName<CampaignState>("state", 24)
    val policyVersion = long("policy_version")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, campaignId)
}

internal object VoucherPoolBatchTable : Table("voucher_pool_batches") {
    val tenantId = varchar("tenant_id", 64)
    val batchId = javaUUID("batch_id")
    val campaignId = javaUUID("campaign_id")
    val state = enumerationByName<BatchState>("state", 32)
    val activatesAt = timestamp("activates_at")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, batchId)
}

internal object VoucherPoolEntryTable : Table("voucher_pool_entries") {
    val tenantId = varchar("tenant_id", 64)
    val entryId = javaUUID("entry_id")
    val campaignId = javaUUID("campaign_id")
    val batchId = javaUUID("batch_id")
    val sourceOrdinal = long("source_ordinal")
    val state = enumerationByName<EntryState>("state", 24)
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, entryId)
}

internal object VoucherPoolReservationTable : Table("voucher_pool_reservations") {
    val tenantId = varchar("tenant_id", 64)
    val reservationId = javaUUID("reservation_id")
    val entryId = javaUUID("entry_id")
    val state = enumerationByName<ReservationState>("state", 24)
    val expiresAt = timestamp("reservation_expires_at")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, reservationId)
}

internal object VoucherPoolUserLimitTable : Table("voucher_pool_user_limits") {
    val tenantId = varchar("tenant_id", 64)
    val campaignId = javaUUID("campaign_id")
    val userDigest = binary("user_digest", 32)
    val activeReservations = integer("active_reservations")
    val activeAllocations = integer("active_allocations")
    val lifetimeConsumed = integer("lifetime_consumed")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, campaignId, userDigest)
}

internal val voucherPoolAuthorityTables = arrayOf(
    VoucherPoolCampaignTable,
    VoucherPoolBatchTable,
    VoucherPoolEntryTable,
    VoucherPoolReservationTable,
    VoucherPoolUserLimitTable,
)
