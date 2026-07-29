package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.exposed.jdbc.repository.UUIDAuditableJdbcRepository
import io.bluetape4k.workshop.commerce.order.domain.CancellationStatus
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.RefundStatus
import io.bluetape4k.workshop.commerce.order.domain.ReservationStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
internal class OrderRepository : UUIDAuditableJdbcRepository<OrderRecord, OrderTable> {
    override val table = OrderTable

    override fun extractId(entity: OrderRecord) = entity.id

    override fun ResultRow.toEntity() =
        OrderRecord(
            id = this[table.id].value,
            tenantId = this[table.tenantId],
            customerReference = this[table.customerReference],
            status = this[table.status],
            revision = this[table.revision],
            providerMode = this[table.providerMode],
            cancelReason = this[table.cancelReason],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun save(record: OrderRecord): OrderRecord {
        table.insert {
            it[id] = record.id
            it[tenantId] = record.tenantId
            it[customerReference] = record.customerReference
            it[status] = record.status
            it[revision] = record.revision
            it[providerMode] = record.providerMode
            it[cancelReason] = record.cancelReason
        }
        return findById(record.id)
    }

    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: OrderStatus,
        to: OrderStatus,
        reason: String? = null,
    ): Boolean =
        auditedUpdateAll({ (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }) {
            it[status] = to
            it[revision] = expectedRevision + 1
            it[cancelReason] = reason?.take(80)
        } == 1
}

@Repository
internal class PaymentAttemptRepository : UUIDAuditableJdbcRepository<PaymentAttemptRecord, PaymentAttemptTable> {
    override val table = PaymentAttemptTable

    override fun extractId(entity: PaymentAttemptRecord) = entity.id

    override fun ResultRow.toEntity() =
        PaymentAttemptRecord(
            id = this[table.id].value,
            orderId = this[table.orderId],
            status = this[table.status],
            revision = this[table.revision],
            providerReference = this[table.providerReference],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun save(record: PaymentAttemptRecord): PaymentAttemptRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[status] = record.status
            it[revision] = record.revision
            it[providerReference] = record.providerReference
        }
        return findById(record.id)
    }

    fun findByOrderId(orderId: UUID): PaymentAttemptRecord? =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.createdAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { with(this) { it.toEntity() } }

    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: PaymentStatus,
        to: PaymentStatus,
        providerRef: String? = null,
    ): Boolean =
        auditedUpdateAll({ (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }) {
            it[status] = to
            it[revision] = expectedRevision + 1
            if (providerRef != null) it[providerReference] = providerRef.take(160)
        } == 1
}

@Repository
internal class InventoryReservationRepository :
    UUIDAuditableJdbcRepository<InventoryReservationRecord, InventoryReservationTable> {
    override val table = InventoryReservationTable

    override fun extractId(entity: InventoryReservationRecord) = entity.id

    override fun ResultRow.toEntity() =
        InventoryReservationRecord(
            id = this[table.id].value,
            orderId = this[table.orderId],
            status = this[table.status],
            revision = this[table.revision],
            reasonCode = this[table.reasonCode],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun save(record: InventoryReservationRecord): InventoryReservationRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[status] = record.status
            it[revision] = record.revision
            it[reasonCode] = record.reasonCode
        }
        return findById(record.id)
    }

    fun findByOrderId(orderId: UUID): InventoryReservationRecord? =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .firstOrNull()
            ?.let { with(this) { it.toEntity() } }

    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: ReservationStatus,
        to: ReservationStatus,
        reason: String? = null,
    ): Boolean =
        auditedUpdateAll({ (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }) {
            it[status] = to
            it[revision] = expectedRevision + 1
            it[reasonCode] = reason?.take(80)
        } == 1
}

@Repository
internal class FulfillmentGroupRepository : UUIDAuditableJdbcRepository<FulfillmentGroupRecord, FulfillmentGroupTable> {
    override val table = FulfillmentGroupTable

    override fun extractId(entity: FulfillmentGroupRecord) = entity.id

    override fun ResultRow.toEntity() =
        FulfillmentGroupRecord(
            id = this[table.id].value,
            orderId = this[table.orderId],
            groupReference = this[table.groupReference],
            status = this[table.status],
            revision = this[table.revision],
            cancelReason = this[table.cancelReason],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun save(record: FulfillmentGroupRecord): FulfillmentGroupRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[groupReference] = record.groupReference
            it[status] = record.status
            it[revision] = record.revision
            it[cancelReason] = record.cancelReason
        }
        return findById(record.id)
    }

    fun findByOrderId(orderId: UUID): List<FulfillmentGroupRecord> =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.groupReference)
            .map { with(this) { it.toEntity() } }

    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: FulfillmentStatus,
        to: FulfillmentStatus,
        reason: String? = null,
    ): Boolean =
        auditedUpdateAll({ (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }) {
            it[status] = to
            it[revision] = expectedRevision + 1
            it[cancelReason] = reason?.take(80)
        } == 1
}

@Repository
internal class CancellationCaseRepository : UUIDAuditableJdbcRepository<CancellationCaseRecord, CancellationCaseTable> {
    override val table = CancellationCaseTable

    override fun extractId(entity: CancellationCaseRecord) = entity.id

    override fun ResultRow.toEntity() =
        CancellationCaseRecord(
            id = this[table.id].value,
            orderId = this[table.orderId],
            lineId = this[table.lineId],
            quantity = this[table.quantity],
            status = this[table.status],
            revision = this[table.revision],
            reasonCode = this[table.reasonCode],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun save(record: CancellationCaseRecord): CancellationCaseRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[lineId] = record.lineId
            it[quantity] = record.quantity
            it[status] = record.status
            it[revision] = record.revision
            it[reasonCode] = record.reasonCode.take(80)
        }
        return findById(record.id)
    }

    fun findByOrderId(orderId: UUID): List<CancellationCaseRecord> =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.createdAt)
            .map { with(this) { it.toEntity() } }

    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: CancellationStatus,
        to: CancellationStatus,
    ): Boolean =
        auditedUpdateAll({ (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }) {
            it[status] = to
            it[revision] = expectedRevision + 1
        } == 1
}

@Repository
internal class RefundCaseRepository : UUIDAuditableJdbcRepository<RefundCaseRecord, RefundCaseTable> {
    override val table = RefundCaseTable

    override fun extractId(entity: RefundCaseRecord) = entity.id

    override fun ResultRow.toEntity() =
        RefundCaseRecord(
            id = this[table.id].value,
            orderId = this[table.orderId],
            status = this[table.status],
            revision = this[table.revision],
            reasonCode = this[table.reasonCode],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun save(record: RefundCaseRecord): RefundCaseRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[status] = record.status
            it[revision] = record.revision
            it[reasonCode] = record.reasonCode.take(80)
        }
        return findById(record.id)
    }

    fun findByOrderId(orderId: UUID): List<RefundCaseRecord> =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.createdAt)
            .map { with(this) { it.toEntity() } }

    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: RefundStatus,
        to: RefundStatus,
    ): Boolean =
        auditedUpdateAll({ (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }) {
            it[status] = to
            it[revision] = expectedRevision + 1
        } == 1
}

@Repository
internal class OrderLineRepository : LongAuditableJdbcRepository<OrderLineRecord, OrderLineTable> {
    override val table = OrderLineTable

    override fun extractId(entity: OrderLineRecord) = entity.id

    override fun ResultRow.toEntity() =
        OrderLineRecord(
            id = this[table.id].value,
            lineId = this[table.lineId],
            orderId = this[table.orderId],
            sku = this[table.sku],
            quantity = this[table.quantity],
            unitPrice = this[table.unitPrice],
            cancelledQuantity = this[table.cancelledQuantity],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun save(record: OrderLineRecord): OrderLineRecord {
        val id =
            table
                .insertAndGetId {
                    it[lineId] = record.lineId
                    it[orderId] = record.orderId
                    it[sku] = record.sku.take(120)
                    it[quantity] = record.quantity
                    it[unitPrice] = record.unitPrice
                    it[cancelledQuantity] = record.cancelledQuantity
                }.value
        return findById(id)
    }

    fun findByOrderId(orderId: UUID): List<OrderLineRecord> =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.id)
            .map { with(this) { it.toEntity() } }

    fun findByLineId(lineId: UUID): OrderLineRecord? =
        table
            .selectAll()
            .where { table.lineId eq lineId }
            .firstOrNull()
            ?.let { with(this) { it.toEntity() } }

    fun cancel(
        lineId: UUID,
        quantity: Int,
    ): Boolean =
        auditedUpdateAll(
            { (table.lineId eq lineId) and (table.cancelledQuantity lessEq (table.quantity - quantity)) }
        ) { it[cancelledQuantity] = table.cancelledQuantity + quantity } == 1
}

@Repository
internal class FulfillmentLineRepository {
    fun save(record: FulfillmentLineRecord): Boolean =
        FulfillmentLineTable
            .insertIgnore {
                it[fulfillmentGroupId] = record.fulfillmentGroupId
                it[lineId] = record.lineId
                it[quantity] = record.quantity
            }.insertedCount == 1

    fun findByOrderId(orderId: UUID): List<FulfillmentLineRecord> {
        val groupIds =
            FulfillmentGroupTable
                .selectAll()
                .where { FulfillmentGroupTable.orderId eq orderId }
                .map { it[FulfillmentGroupTable.id].value }
        if (groupIds.isEmpty()) return emptyList()

        return FulfillmentLineTable
            .selectAll()
            .where { FulfillmentLineTable.fulfillmentGroupId inList groupIds }
            .orderBy(FulfillmentLineTable.fulfillmentGroupId, SortOrder.ASC)
            .map(::toRecord)
    }

    fun findByLineId(lineId: UUID): List<FulfillmentLineRecord> =
        FulfillmentLineTable
            .selectAll()
            .where { FulfillmentLineTable.lineId eq lineId }
            .map(::toRecord)

    fun cancel(
        lineId: UUID,
        cancellableGroupIds: Set<UUID>,
        cancelledQuantity: Int,
    ): Boolean {
        if (cancellableGroupIds.isEmpty()) return false
        val links =
            FulfillmentLineTable
                .selectAll()
                .where {
                    (FulfillmentLineTable.lineId eq lineId) and
                        (FulfillmentLineTable.fulfillmentGroupId inList cancellableGroupIds)
                }.orderBy(FulfillmentLineTable.fulfillmentGroupId, SortOrder.ASC)
                .map(::toRecord)
        if (links.sumOf { it.quantity } < cancelledQuantity) return false

        var remaining = cancelledQuantity
        links.forEach { link ->
            if (remaining > 0) {
                val decrement = minOf(link.quantity, remaining)
                val updated =
                    FulfillmentLineTable.update(
                        where = {
                            (FulfillmentLineTable.fulfillmentGroupId eq link.fulfillmentGroupId) and
                                (FulfillmentLineTable.lineId eq lineId) and
                                (FulfillmentLineTable.quantity eq link.quantity)
                        }
                    ) {
                        it[quantity] = link.quantity - decrement
                    }
                if (updated != 1) return false
                remaining -= decrement
            }
        }
        return remaining == 0
    }

    private fun toRecord(row: ResultRow): FulfillmentLineRecord =
        FulfillmentLineRecord(
            fulfillmentGroupId = row[FulfillmentLineTable.fulfillmentGroupId],
            lineId = row[FulfillmentLineTable.lineId],
            quantity = row[FulfillmentLineTable.quantity]
        )
}

@Repository
internal class LifecycleAuditRepository : LongJdbcRepository<LifecycleAuditRecord> {
    override val table = LifecycleAuditTable

    override fun extractId(entity: LifecycleAuditRecord) = entity.id

    override fun ResultRow.toEntity() =
        LifecycleAuditRecord(
            id = this[table.id].value,
            eventId = this[table.eventId],
            orderId = this[table.orderId],
            aggregateType = this[table.aggregateType],
            aggregateId = this[table.aggregateId],
            revision = this[table.revision],
            fromStatus = this[table.fromStatus],
            toStatus = this[table.toStatus],
            reasonCode = this[table.reasonCode],
            actorType = this[table.actorType],
            occurredAt = this[table.occurredAt]
        )

    fun append(record: LifecycleAuditRecord): LifecycleAuditRecord {
        val id =
            table
                .insertAndGetId {
                    it[eventId] = record.eventId
                    it[orderId] = record.orderId
                    it[aggregateType] = record.aggregateType
                    it[aggregateId] = record.aggregateId
                    it[revision] = record.revision
                    it[fromStatus] = record.fromStatus
                    it[toStatus] = record.toStatus
                    it[reasonCode] = record.reasonCode?.take(80)
                    it[actorType] = record.actorType.take(40)
                }.value
        return findById(id)
    }

    fun findByOrderId(
        orderId: UUID,
        afterId: Long = 0,
    ): List<LifecycleAuditRecord> =
        table
            .selectAll()
            .where { (table.orderId eq orderId) and (table.id greater afterId) }
            .orderBy(table.id)
            .map { with(this) { it.toEntity() } }
}
