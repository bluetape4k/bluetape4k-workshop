package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.workshop.commerce.metering.domain.CommandReceiptStatus
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptScope
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptSnapshot
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class CommandReceiptOwnerInsert(
    val scope: CommandReceiptScope,
    val requestFingerprint: String,
    val ownerToken: UUID,
    val now: Instant,
    val leaseDeadline: Instant,
    val retentionDeadline: Instant,
)

data class CommandReceiptCompletion(
    val receiptId: UUID,
    val ownerToken: UUID,
    val status: CommandReceiptStatus,
    val httpStatus: Int,
    val response: String,
    val now: Instant,
    val retentionDeadline: Instant,
)

data class MeterInsert(
    val id: UUID,
    val tenantId: String,
    val meterCode: String,
    val unit: String,
    val description: String?,
    val now: Instant,
)

data class UsageCloseQuery(
    val tenantId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val cutoff: Instant,
    val lastOccurredAt: Instant?,
    val lastId: UUID?,
    val limit: Int,
)

@Repository
class CommandReceiptRepository :
    MeteringExposedJdbcRepository<CommandReceiptEntity, UUID>(CommandReceiptEntity::class.java) {

    fun insertOwnerIfAbsent(request: CommandReceiptOwnerInsert): Boolean =
        CommandReceipts.insertIgnore {
            it[tenantId] = request.scope.tenantId
            it[operation] = request.scope.operation
            it[keyDigest] = request.scope.keyDigest
            it[requestFingerprint] = request.requestFingerprint
            it[status] = CommandReceiptStatus.IN_PROGRESS.name
            it[ownerToken] = request.ownerToken
            it[leaseDeadline] = request.leaseDeadline
            it[retentionDeadline] = request.retentionDeadline
            it[createdAt] = request.now
            it[updatedAt] = request.now
        }.insertedCount == 1

    fun find(scope: CommandReceiptScope): CommandReceiptSnapshot? =
        CommandReceiptEntity.find {
            (CommandReceipts.tenantId eq scope.tenantId) and
                (CommandReceipts.operation eq scope.operation) and
                (CommandReceipts.keyDigest eq scope.keyDigest)
        }.firstOrNull()?.toSnapshot()

    fun takeover(
        current: CommandReceiptSnapshot,
        ownerToken: UUID,
        now: Instant,
        leaseDeadline: Instant,
        retentionDeadline: Instant,
    ): Boolean =
        CommandReceipts.update(
            where = {
                (CommandReceipts.id eq current.id) and
                    (CommandReceipts.status eq CommandReceiptStatus.IN_PROGRESS.name) and
                    (CommandReceipts.ownerToken eq current.ownerToken) and
                    (CommandReceipts.leaseDeadline eq current.leaseDeadline)
            },
        ) {
            it[CommandReceipts.ownerToken] = ownerToken
            it[CommandReceipts.leaseDeadline] = leaseDeadline
            it[CommandReceipts.retentionDeadline] = retentionDeadline
            it[updatedAt] = now
        } == 1

    fun complete(completion: CommandReceiptCompletion): Boolean =
        CommandReceipts.update(
            where = {
                (CommandReceipts.id eq completion.receiptId) and
                    (CommandReceipts.ownerToken eq completion.ownerToken) and
                    (CommandReceipts.status eq CommandReceiptStatus.IN_PROGRESS.name)
            },
        ) {
            it[status] = completion.status.name
            it[terminalAt] = completion.now
            it[terminalHttpStatus] = completion.httpStatus
            it[terminalResponse] = completion.response
            it[retentionDeadline] = completion.retentionDeadline
            it[updatedAt] = completion.now
        } == 1

    fun deleteExpiredTerminalBatch(now: Instant, limit: Int): Int {
        val ids =
            CommandReceipts
                .selectAll()
                .where {
                    ((CommandReceipts.status eq CommandReceiptStatus.SUCCEEDED.name) or
                        (CommandReceipts.status eq CommandReceiptStatus.FAILED.name)) and
                        (CommandReceipts.retentionDeadline less now)
                }
                .orderBy(CommandReceipts.retentionDeadline to SortOrder.ASC, CommandReceipts.id to SortOrder.ASC)
                .limit(limit)
                .map { it[CommandReceipts.id] }
        if (ids.isEmpty()) return 0
        return CommandReceipts.deleteWhere {
            (CommandReceipts.id inList ids) and
                ((CommandReceipts.status eq CommandReceiptStatus.SUCCEEDED.name) or
                    (CommandReceipts.status eq CommandReceiptStatus.FAILED.name)) and
                (CommandReceipts.retentionDeadline less now)
        }
    }

    private fun CommandReceiptEntity.toSnapshot(): CommandReceiptSnapshot =
        CommandReceiptSnapshot(
            id = id.value,
            requestFingerprint = requestFingerprint,
            status = CommandReceiptStatus.valueOf(status),
            ownerToken = ownerToken,
            leaseDeadline = leaseDeadline,
            terminalHttpStatus = terminalHttpStatus,
            terminalResponse = terminalResponse,
        )
}

@Repository
class MeterRepository : MeteringExposedJdbcRepository<MeterEntity, UUID>(MeterEntity::class.java) {
    fun find(tenantId: String, meterCode: String): MeterEntity? =
        MeterEntity.find { (Meters.tenantId eq tenantId) and (Meters.meterCode eq meterCode) }.firstOrNull()

    fun createIfAbsent(request: MeterInsert): Boolean =
        Meters.insertIgnore {
            it[Meters.id] = request.id
            it[tenantId] = request.tenantId
            it[meterCode] = request.meterCode
            it[unit] = request.unit
            it[description] = request.description
            it[active] = true
            it[createdAt] = request.now
        }.insertedCount == 1
}

@Repository
class PricingScheduleRepository :
    MeteringExposedJdbcRepository<PricingScheduleEntity, UUID>(PricingScheduleEntity::class.java) {
    fun getOrCreate(id: UUID, tenantId: String, meterId: UUID, currency: String, now: Instant): PricingScheduleEntity {
        PricingSchedules.insertIgnore {
            it[PricingSchedules.id] = id
            it[PricingSchedules.tenantId] = tenantId
            it[PricingSchedules.meterId] = meterId
            it[PricingSchedules.currency] = currency
            it[createdAt] = now
        }
        PricingSchedules.selectAll()
            .where {
                (PricingSchedules.tenantId eq tenantId) and
                    (PricingSchedules.meterId eq meterId) and
                    (PricingSchedules.currency eq currency)
            }
            .forUpdate()
            .single()
        return PricingScheduleEntity.find {
            (PricingSchedules.tenantId eq tenantId) and
                (PricingSchedules.meterId eq meterId) and
                (PricingSchedules.currency eq currency)
        }.single()
    }
}

@Repository
class PriceVersionRepository :
    AppendOnlyMeteringExposedJdbcRepository<PriceVersionEntity, UUID>(PriceVersionEntity::class.java) {
    fun timeline(scheduleId: UUID): List<PriceVersionEntity> =
        PriceVersionEntity.find { PriceVersions.scheduleId eq scheduleId }
            .orderBy(PriceVersions.effectiveFrom to SortOrder.ASC)
            .toList()

    fun select(tenantId: String, meterId: UUID, currency: String, occurredAt: Instant): PriceVersionEntity? =
        PriceVersionEntity.find {
            (PriceVersions.tenantId eq tenantId) and
                (PriceVersions.meterId eq meterId) and
                (PriceVersions.currency eq currency) and
                (PriceVersions.effectiveFrom lessEq occurredAt) and
                ((PriceVersions.effectiveTo greater occurredAt) or PriceVersions.effectiveTo.isNull())
        }.orderBy(PriceVersions.effectiveFrom to SortOrder.DESC).firstOrNull()

    fun isReferenced(priceVersionId: UUID): Boolean =
        LedgerEntries.selectAll().where { LedgerEntries.priceVersionId eq priceVersionId }.limit(1).any()
}

@Repository
class UsageEventRepository :
    AppendOnlyMeteringExposedJdbcRepository<UsageEventEntity, UUID>(UsageEventEntity::class.java) {
    fun findSource(tenantId: String, sourceSystem: String, sourceEventId: String): UsageEventEntity? =
        UsageEventEntity.find {
            (UsageEvents.tenantId eq tenantId) and
                (UsageEvents.sourceSystem eq sourceSystem) and
                (UsageEvents.sourceEventId eq sourceEventId)
        }.firstOrNull()

    fun closeBatch(query: UsageCloseQuery): List<UsageEventEntity> =
        UsageEventEntity.find {
            val afterCheckpoint =
                if (query.lastOccurredAt == null || query.lastId == null) {
                    UsageEvents.occurredAt greaterEq query.startsAt
                } else {
                    (UsageEvents.occurredAt greater query.lastOccurredAt) or
                        ((UsageEvents.occurredAt eq query.lastOccurredAt) and (UsageEvents.id greater query.lastId))
                }
            (UsageEvents.tenantId eq query.tenantId) and
                (UsageEvents.occurredAt greaterEq query.startsAt) and
                (UsageEvents.occurredAt less query.endsAt) and
                (UsageEvents.receivedAt lessEq query.cutoff) and afterCheckpoint
        }.orderBy(UsageEvents.occurredAt to SortOrder.ASC, UsageEvents.id to SortOrder.ASC)
            .limit(query.limit)
            .toList()
}

@Repository
class BillingCalendarRepository :
    MeteringExposedJdbcRepository<BillingCalendarEntity, UUID>(BillingCalendarEntity::class.java)

@Repository
class BillingPeriodRepository :
    MeteringExposedJdbcRepository<BillingPeriodEntity, UUID>(BillingPeriodEntity::class.java) {
    fun findTenant(id: UUID, tenantId: String): BillingPeriodEntity? =
        BillingPeriodEntity.find { (BillingPeriods.id eq id) and (BillingPeriods.tenantId eq tenantId) }.firstOrNull()

    fun overlapping(calendarId: UUID, startsAt: Instant, endsAt: Instant): Boolean =
        BillingPeriods.selectAll().where {
            (BillingPeriods.calendarId eq calendarId) and
                (BillingPeriods.startsAt less endsAt) and
                (BillingPeriods.endsAt greater startsAt)
        }.limit(1).any()

    fun openAt(tenantId: String, currency: String, instant: Instant): BillingPeriodEntity? =
        BillingPeriodEntity.find {
            (BillingPeriods.tenantId eq tenantId) and
                (BillingPeriods.currency eq currency) and
                (BillingPeriods.state eq "OPEN") and
                (BillingPeriods.startsAt lessEq instant) and
                (BillingPeriods.endsAt greater instant)
        }.firstOrNull()
}

@Repository
class CloseRunRepository : MeteringExposedJdbcRepository<CloseRunEntity, UUID>(CloseRunEntity::class.java) {
    fun findTenant(id: UUID, tenantId: String): CloseRunEntity? =
        CloseRunEntity.find { (CloseRuns.id eq id) and (CloseRuns.tenantId eq tenantId) }.firstOrNull()

    fun running(limit: Int): List<CloseRunEntity> =
        CloseRunEntity.find { CloseRuns.state eq "RUNNING" }
            .orderBy(CloseRuns.updatedAt to SortOrder.ASC, CloseRuns.id to SortOrder.ASC)
            .limit(limit)
            .toList()
}

@Repository
class LedgerEntryRepository :
    AppendOnlyMeteringExposedJdbcRepository<LedgerEntryEntity, UUID>(LedgerEntryEntity::class.java) {
    fun forPostingPeriod(tenantId: String, periodId: UUID): List<LedgerEntryEntity> =
        LedgerEntryEntity.find {
            (LedgerEntries.tenantId eq tenantId) and (LedgerEntries.postingPeriodId eq periodId)
        }.orderBy(LedgerEntries.id to SortOrder.ASC).toList()

    fun findSource(tenantId: String, type: String, sourceType: String, sourceId: String): LedgerEntryEntity? =
        LedgerEntryEntity.find {
            (LedgerEntries.tenantId eq tenantId) and
                (LedgerEntries.entryType eq type) and
                (LedgerEntries.sourceReferenceType eq sourceType) and
                (LedgerEntries.sourceReferenceId eq sourceId)
        }.firstOrNull()
}

@Repository
class InvoiceRepository :
    AppendOnlyMeteringExposedJdbcRepository<InvoiceEntity, UUID>(InvoiceEntity::class.java) {
    fun findPeriod(tenantId: String, periodId: UUID, currency: String): InvoiceEntity? =
        InvoiceEntity.find {
            (Invoices.tenantId eq tenantId) and
                (Invoices.periodId eq periodId) and
                (Invoices.currency eq currency)
        }.firstOrNull()
}

@Repository
class InvoiceLineRepository :
    AppendOnlyMeteringExposedJdbcRepository<InvoiceLineEntity, UUID>(InvoiceLineEntity::class.java)

@Repository
class InvoiceLineEntryRepository :
    AppendOnlyMeteringExposedJdbcRepository<InvoiceLineEntryEntity, UUID>(InvoiceLineEntryEntity::class.java)

@Repository
class ReconciliationRunRepository :
    MeteringExposedJdbcRepository<ReconciliationRunEntity, UUID>(ReconciliationRunEntity::class.java)

@Repository
class ReconciliationFindingRepository :
    AppendOnlyMeteringExposedJdbcRepository<ReconciliationFindingEntity, UUID>(
        ReconciliationFindingEntity::class.java,
    )
