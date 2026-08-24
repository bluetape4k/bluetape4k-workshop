package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.CommittedAllocationPin
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Order
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLine
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanProposal
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReservationState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.SkuStockSnapshot
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Warehouse
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEvent
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationNextAction
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PickWave
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PickWaveStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PinStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationEventInboxTable.aggregateId
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class WarehouseAllocationRepository(
    private val codec: WarehouseAllocationCodec = WarehouseAllocationCodec(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun saveWarehouse(value: Warehouse): Warehouse {
        val now = clock.instant()
        WarehouseAllocationWarehousesTable.insert { statement ->
            statement[warehouseId] = value.warehouseId.value
            statement[payload] = codec.encode(value)
            statement[revision] = value.revision
            statement[updatedAt] = now
        }
        return value
    }

    fun findWarehouse(warehouseId: String): Warehouse? = WarehouseAllocationWarehousesTable.selectAll()
        .where { WarehouseAllocationWarehousesTable.warehouseId eq warehouseId }.singleOrNull()
        ?.let { row -> codec.decode(row[WarehouseAllocationWarehousesTable.payload], Warehouse::class.java).copy(revision = row[WarehouseAllocationWarehousesTable.revision]) }

    fun listWarehouses(limit: Int = WarehouseAllocationLimits.MAX_WAREHOUSES): List<Warehouse> =
        WarehouseAllocationWarehousesTable.selectAll()
            .orderBy(WarehouseAllocationWarehousesTable.warehouseId to SortOrder.ASC)
            .limit(limit.coerceIn(1, WarehouseAllocationLimits.MAX_WAREHOUSES))
            .map { row -> codec.decode(row[WarehouseAllocationWarehousesTable.payload], Warehouse::class.java).copy(revision = row[WarehouseAllocationWarehousesTable.revision]) }

    fun saveStock(value: SkuStockSnapshot): SkuStockSnapshot {
        WarehouseAllocationStockTable.insert { statement ->
            statement[warehouseId] = value.warehouseId.value
            statement[sku] = value.sku.value
            statement[onHandQuantity] = value.onHandQuantity
            statement[reservedQuantity] = value.reservedQuantity
            statement[revision] = value.stockRevision
            statement[sourceEventRevision] = value.sourceEventRevision
            statement[payload] = codec.encode(value)
            statement[updatedAt] = value.updatedAt
        }
        return value
    }

    fun findStock(warehouseId: String, sku: String): SkuStockSnapshot? = WarehouseAllocationStockTable.selectAll()
        .where { (WarehouseAllocationStockTable.warehouseId eq warehouseId) and (WarehouseAllocationStockTable.sku eq sku) }
        .singleOrNull()?.let { row ->
            codec.decode(row[WarehouseAllocationStockTable.payload], SkuStockSnapshot::class.java).copy(
                stockRevision = row[WarehouseAllocationStockTable.revision],
                sourceEventRevision = row[WarehouseAllocationStockTable.sourceEventRevision],
            )
        }

    fun listStock(limit: Int = 100): List<SkuStockSnapshot> = WarehouseAllocationStockTable.selectAll()
        .orderBy(WarehouseAllocationStockTable.warehouseId to SortOrder.ASC, WarehouseAllocationStockTable.sku to SortOrder.ASC)
        .limit(limit.coerceIn(1, WarehouseAllocationLimits.MAX_STOCK_ROWS))
        .map { row ->
            codec.decode(row[WarehouseAllocationStockTable.payload], SkuStockSnapshot::class.java).copy(
                stockRevision = row[WarehouseAllocationStockTable.revision],
                sourceEventRevision = row[WarehouseAllocationStockTable.sourceEventRevision],
            )
        }

    fun saveOrder(value: Order): Order {
        val now = clock.instant()
        WarehouseAllocationOrdersTable.insert { statement ->
            statement[orderId] = value.orderId.value
            statement[WarehouseAllocationOrdersTable.status] = value.status
            statement[revision] = value.revision
            statement[payload] = codec.encode(value)
            statement[updatedAt] = now
        }
        value.lines.forEach { line ->
            val storedLine = if (line.orderId == value.orderId) line else line.copy(orderId = value.orderId)
            WarehouseAllocationOrderLinesTable.insert { statement ->
                statement[orderLineId] = storedLine.orderLineId.value
                statement[orderId] = value.orderId.value
                statement[sku] = storedLine.sku.value
                statement[requestedQuantity] = storedLine.requestedQuantity
                statement[status] = storedLine.status
                statement[revision] = storedLine.revision
                statement[payload] = codec.encode(storedLine)
                statement[updatedAt] = now
            }
        }
        return value
    }

    fun findOrder(orderId: String): Order? = WarehouseAllocationOrdersTable.selectAll()
        .where { WarehouseAllocationOrdersTable.orderId eq orderId }.singleOrNull()
        ?.let { row ->
            val decoded = codec.decode(row[WarehouseAllocationOrdersTable.payload], Order::class.java)
            decoded.copy(
                status = row[WarehouseAllocationOrdersTable.status],
                revision = row[WarehouseAllocationOrdersTable.revision],
                lines = orderLines(orderId).ifEmpty { decoded.lines },
            )
        }

    fun listOrders(limit: Int = WarehouseAllocationLimits.MAX_LINES): List<Order> =
        WarehouseAllocationOrdersTable.selectAll()
            .orderBy(WarehouseAllocationOrdersTable.orderId to SortOrder.ASC)
            .limit(limit.coerceIn(1, WarehouseAllocationLimits.MAX_LINES))
            .mapNotNull { row -> findOrder(row[WarehouseAllocationOrdersTable.orderId]) }

    fun findOrderLine(orderLineId: String): OrderLine? = WarehouseAllocationOrderLinesTable.selectAll()
        .where { WarehouseAllocationOrderLinesTable.orderLineId eq orderLineId }.singleOrNull()
        ?.let { row ->
            codec.decode(row[WarehouseAllocationOrderLinesTable.payload], OrderLine::class.java).copy(
                orderId = OrderId(row[WarehouseAllocationOrderLinesTable.orderId]),
            )
        }

    fun updateOrderLine(orderLineId: String, expectedRevision: Long, next: OrderLine): Boolean {
        val current = WarehouseAllocationOrderLinesTable.selectAll().where {
            WarehouseAllocationOrderLinesTable.orderLineId eq orderLineId
        }.singleOrNull() ?: return false
        val stored = if (next.orderId == null) next.copy(orderId = OrderId(current[WarehouseAllocationOrderLinesTable.orderId])) else next
        return WarehouseAllocationOrderLinesTable.update({
            (WarehouseAllocationOrderLinesTable.orderLineId eq orderLineId) and
                (WarehouseAllocationOrderLinesTable.revision eq expectedRevision)
        }) { statement ->
            statement[status] = stored.status
            statement[revision] = stored.revision
            statement[payload] = codec.encode(stored)
            statement[updatedAt] = clock.instant()
        } == 1
    }

    fun orderLineIds(orderId: String): List<String> = WarehouseAllocationOrderLinesTable.selectAll()
        .where { WarehouseAllocationOrderLinesTable.orderId eq orderId }
        .orderBy(WarehouseAllocationOrderLinesTable.orderLineId to SortOrder.ASC)
        .map { it[WarehouseAllocationOrderLinesTable.orderLineId] }

    fun orderLines(orderId: String): List<OrderLine> = orderLineIds(orderId).mapNotNull(::findOrderLine)

    fun activePin(orderLineId: String): CommittedAllocationPin? = WarehouseAllocationPinsTable.selectAll()
        .where { (WarehouseAllocationPinsTable.orderLineId eq orderLineId) and (WarehouseAllocationPinsTable.status eq PinStatus.ACTIVE) }
        .orderBy(WarehouseAllocationPinsTable.revision to SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.let { codec.decode(it[WarehouseAllocationPinsTable.payload], CommittedAllocationPin::class.java) }

    fun listActivePins(limit: Int = WarehouseAllocationLimits.MAX_PINS): List<CommittedAllocationPin> =
        WarehouseAllocationPinsTable.selectAll()
            .where { WarehouseAllocationPinsTable.status eq PinStatus.ACTIVE }
            .orderBy(WarehouseAllocationPinsTable.orderLineId to SortOrder.ASC, WarehouseAllocationPinsTable.revision to SortOrder.DESC)
            .limit(limit.coerceIn(1, WarehouseAllocationLimits.MAX_PINS))
            .map { codec.decode(it[WarehouseAllocationPinsTable.payload], CommittedAllocationPin::class.java) }

    fun activePlanId(orderLineId: String): String? = WarehouseAllocationOrderLinesTable.selectAll()
        .where { WarehouseAllocationOrderLinesTable.orderLineId eq orderLineId }
        .singleOrNull()?.get(WarehouseAllocationOrderLinesTable.activePlanId)

    fun findWave(waveId: String): PickWave? = WarehouseAllocationWavesTable.selectAll()
        .where { WarehouseAllocationWavesTable.waveId eq waveId }.singleOrNull()
        ?.let { row -> codec.decode(row[WarehouseAllocationWavesTable.payload], PickWave::class.java).copy(
            revision = row[WarehouseAllocationWavesTable.revision],
            status = row[WarehouseAllocationWavesTable.status],
        ) }

    fun listWaves(limit: Int = WarehouseAllocationLimits.MAX_WAVES): List<PickWave> =
        WarehouseAllocationWavesTable.selectAll()
            .orderBy(WarehouseAllocationWavesTable.waveId to SortOrder.ASC)
            .limit(limit.coerceIn(1, WarehouseAllocationLimits.MAX_WAVES))
            .map { row -> codec.decode(row[WarehouseAllocationWavesTable.payload], PickWave::class.java).copy(
                revision = row[WarehouseAllocationWavesTable.revision],
                status = row[WarehouseAllocationWavesTable.status],
            ) }

    fun allocationLineCount(waveId: String): Int = WarehouseAllocationAllocationsTable.selectAll()
        .where { WarehouseAllocationAllocationsTable.waveId eq waveId }
        .map { it[WarehouseAllocationAllocationsTable.planId] to it[WarehouseAllocationAllocationsTable.orderLineId] }
        .filter { (planId, _) ->
            WarehouseAllocationPlansTable.selectAll()
                .where {
                    (WarehouseAllocationPlansTable.planId eq planId) and
                        (WarehouseAllocationPlansTable.status eq PlanStatus.APPROVED)
                }
                .limit(1)
                .any()
        }
        .map { (_, orderLineId) -> orderLineId }
        .distinct()
        .size

    fun updateWaveIfRevision(waveId: String, expectedRevision: Long, next: PickWave): Boolean =
        WarehouseAllocationWavesTable.update({
            (WarehouseAllocationWavesTable.waveId eq waveId) and
                (WarehouseAllocationWavesTable.revision eq expectedRevision)
        }) { statement ->
            statement[WarehouseAllocationWavesTable.status] = next.status
            statement[WarehouseAllocationWavesTable.revision] = next.revision
            statement[WarehouseAllocationWavesTable.payload] = codec.encode(next)
            statement[WarehouseAllocationWavesTable.updatedAt] = clock.instant()
        } == 1

    fun claimActivePlan(orderLineId: String, expectedRevision: Long, planId: String): Boolean =
        WarehouseAllocationOrderLinesTable.update({
            (WarehouseAllocationOrderLinesTable.orderLineId eq orderLineId) and
                (WarehouseAllocationOrderLinesTable.revision eq expectedRevision) and
                (WarehouseAllocationOrderLinesTable.activePlanId.isNull() or (WarehouseAllocationOrderLinesTable.activePlanId eq planId))
        }) { statement ->
            statement[activePlanId] = planId
            statement[revision] = expectedRevision + 1
            statement[updatedAt] = clock.instant()
        } == 1

    fun clearActivePlan(orderLineId: String, expectedRevision: Long, planId: String? = null): Boolean =
        WarehouseAllocationOrderLinesTable.update({
            val base = (WarehouseAllocationOrderLinesTable.orderLineId eq orderLineId) and
                (WarehouseAllocationOrderLinesTable.revision eq expectedRevision)
            if (planId == null) base else base and
                (WarehouseAllocationOrderLinesTable.activePlanId.isNull() or (WarehouseAllocationOrderLinesTable.activePlanId eq planId))
        }) { statement ->
            statement[activePlanId] = null
            statement[revision] = expectedRevision + 1
            statement[updatedAt] = clock.instant()
        } == 1

    fun updateOrderIfRevision(orderId: String, expectedRevision: Long, next: Order): Boolean =
        WarehouseAllocationOrdersTable.update(
            where = { (WarehouseAllocationOrdersTable.orderId eq orderId) and (WarehouseAllocationOrdersTable.revision eq expectedRevision) },
        ) { statement ->
            statement[status] = next.status
            statement[revision] = next.revision
            statement[payload] = codec.encode(next)
            statement[updatedAt] = clock.instant()
        } == 1

    fun cancelOrderLine(orderLineId: String, expectedRevision: Long): Boolean {
        val line = findOrderLine(orderLineId) ?: return false
        if (line.status == OrderLineStatus.CANCELLED) return true
        if (line.revision != expectedRevision || line.status == OrderLineStatus.FULFILLED) return false
        if (!updateOrderLine(orderLineId, expectedRevision, line.copy(status = OrderLineStatus.CANCELLED, revision = expectedRevision + 1))) return false
        WarehouseAllocationOrderLinesTable.update({ WarehouseAllocationOrderLinesTable.orderLineId eq orderLineId }) { statement ->
            statement[activePlanId] = null
        }
        releaseReservationsForLine(orderLineId)
        return true
    }

    fun projectOrderStatus(lines: List<OrderLine>): OrderStatus = when {
        lines.isNotEmpty() && lines.all { it.status == OrderLineStatus.CANCELLED } -> OrderStatus.CANCELLED
        lines.isNotEmpty() && lines.all { it.status == OrderLineStatus.FULFILLED } -> OrderStatus.COMPLETED
        lines.isNotEmpty() && lines.all { it.status == OrderLineStatus.OPEN } -> OrderStatus.OPEN
        else -> OrderStatus.PARTIALLY_ALLOCATED
    }

    fun updateWarehouseIfRevision(warehouseId: String, expectedRevision: Long, next: Warehouse): Boolean =
        WarehouseAllocationWarehousesTable.update({
            (WarehouseAllocationWarehousesTable.warehouseId eq warehouseId) and
                (WarehouseAllocationWarehousesTable.revision eq expectedRevision)
        }) { statement ->
            statement[WarehouseAllocationWarehousesTable.revision] = next.revision
            statement[WarehouseAllocationWarehousesTable.payload] = codec.encode(next)
            statement[WarehouseAllocationWarehousesTable.updatedAt] = clock.instant()
        } == 1

    fun updateOrderLineCutoff(orderLineId: String, cutoffAt: Instant): Boolean {
        val current = findOrderLine(orderLineId) ?: return false
        return updateOrderLine(orderLineId, current.revision, current.copy(carrierCutoff = cutoffAt, revision = current.revision + 1))
    }

    fun updateWarehouseCapacity(warehouseId: String, capacity: Int): Boolean {
        val current = findWarehouse(warehouseId) ?: return false
        return updateWarehouseIfRevision(warehouseId, current.revision, current.copy(pickerCapacity = capacity, revision = current.revision + 1))
    }

    fun updateWarehouseIncident(warehouseId: String, active: Boolean): Boolean {
        val current = findWarehouse(warehouseId) ?: return false
        return updateWarehouseIfRevision(warehouseId, current.revision, current.copy(incident = active, revision = current.revision + 1))
    }

    fun saveWave(value: PickWave): PickWave {
        WarehouseAllocationWavesTable.insert { statement ->
            statement[waveId] = value.waveId.value
            statement[warehouseId] = value.warehouseId.value
            statement[status] = value.status
            statement[revision] = value.revision
            statement[payload] = codec.encode(value)
            statement[updatedAt] = clock.instant()
        }
        return value
    }

    fun savePin(value: CommittedAllocationPin): CommittedAllocationPin {
        WarehouseAllocationPinsTable.insert { statement ->
            statement[pinId] = value.pinId.value
            statement[orderLineId] = value.orderLineId.value
            statement[warehouseId] = value.warehouseId.value
            statement[quantity] = value.quantity
            statement[revision] = value.pinRevision
            statement[status] = value.status
            statement[createdBy] = value.createdBy
            statement[payload] = codec.encode(value)
            statement[updatedAt] = clock.instant()
        }
        return value
    }

    fun findPin(pinId: String): CommittedAllocationPin? = WarehouseAllocationPinsTable.selectAll()
        .where { WarehouseAllocationPinsTable.pinId eq pinId }.singleOrNull()
        ?.let { codec.decode(it[WarehouseAllocationPinsTable.payload], CommittedAllocationPin::class.java) }

    fun updatePin(pinId: String, expectedRevision: Long, next: CommittedAllocationPin): Boolean =
        WarehouseAllocationPinsTable.update({
            (WarehouseAllocationPinsTable.pinId eq pinId) and
                (WarehouseAllocationPinsTable.revision eq expectedRevision)
        }) { statement ->
            statement[status] = next.status
            statement[revision] = next.pinRevision
            statement[payload] = codec.encode(next)
            statement[updatedAt] = clock.instant()
        } == 1

    fun savePlan(value: PlanProposal): PlanProposal {
        val now = clock.instant()
        WarehouseAllocationPlansTable.insert { statement ->
            statement[planId] = value.planId.value
            statement[datasetId] = value.datasetId.value
            statement[datasetVersion] = value.datasetVersion
            statement[planRevision] = value.planRevision
            statement[expectedOrderRevision] = value.expectedOrderRevision
            statement[warehouseRevision] = value.warehouseRevision
            statement[status] = value.status
            statement[payload] = codec.encode(value)
            statement[digest] = value.digest
            statement[fencingToken] = value.fencingToken
            statement[requestGeneration] = value.requestGeneration
            statement[createdAt] = now
            statement[updatedAt] = now
        }
        value.allocations.forEach { allocation ->
            WarehouseAllocationAllocationsTable.insert { statement ->
                statement[planId] = value.planId.value
                statement[orderLineId] = allocation.orderLineId.value
                statement[warehouseId] = allocation.warehouseId.value
                statement[waveId] = allocation.waveId.value
                statement[quantity] = allocation.quantity
            }
        }
        return value
    }

    fun findPlan(planId: String, revision: Long? = null): PlanProposal? = WarehouseAllocationPlansTable.selectAll()
        .where {
            if (revision == null) WarehouseAllocationPlansTable.planId eq planId
            else (WarehouseAllocationPlansTable.planId eq planId) and (WarehouseAllocationPlansTable.planRevision eq revision)
        }.orderBy(WarehouseAllocationPlansTable.planRevision to SortOrder.DESC).limit(1).singleOrNull()
        ?.let { codec.decode(it[WarehouseAllocationPlansTable.payload], PlanProposal::class.java) }

    fun listPlans(limit: Int = 100): List<PlanProposal> = WarehouseAllocationPlansTable.selectAll()
        .orderBy(WarehouseAllocationPlansTable.planRevision to SortOrder.DESC)
        .limit(limit.coerceIn(1, 100)).map { codec.decode(it[WarehouseAllocationPlansTable.payload], PlanProposal::class.java) }

    fun updatePlanStatus(planId: String, expectedRevision: Long, next: PlanProposal): Boolean =
        WarehouseAllocationPlansTable.update(
            where = { (WarehouseAllocationPlansTable.planId eq planId) and (WarehouseAllocationPlansTable.planRevision eq expectedRevision) },
        ) { statement ->
            statement[status] = next.status
            statement[planRevision] = next.planRevision
            statement[payload] = codec.encode(next)
            statement[updatedAt] = clock.instant()
        } == 1

    fun insertReservation(record: WarehouseAllocationReservationRecord): Boolean =
        WarehouseAllocationReservationsTable.insertIgnore { statement ->
            statement[reservationId] = record.reservationId
            statement[planId] = record.planId
            statement[orderLineId] = record.orderLineId
            statement[warehouseId] = record.warehouseId
            statement[sku] = record.sku
            statement[quantity] = record.quantity
            statement[state] = record.state
            statement[revision] = record.revision
            statement[updatedAt] = clock.instant()
        }.insertedCount == 1

    fun reservations(orderLineId: String): List<WarehouseAllocationReservationRecord> = WarehouseAllocationReservationsTable.selectAll()
        .where { WarehouseAllocationReservationsTable.orderLineId eq orderLineId }
        .orderBy(WarehouseAllocationReservationsTable.reservationId to SortOrder.ASC)
        .map { row ->
            WarehouseAllocationReservationRecord(
                row[WarehouseAllocationReservationsTable.reservationId], row[WarehouseAllocationReservationsTable.planId],
                row[WarehouseAllocationReservationsTable.orderLineId], row[WarehouseAllocationReservationsTable.warehouseId],
                row[WarehouseAllocationReservationsTable.sku], row[WarehouseAllocationReservationsTable.quantity],
                row[WarehouseAllocationReservationsTable.state], row[WarehouseAllocationReservationsTable.revision],
            )
        }

    fun reservationsForPlan(planId: String): List<WarehouseAllocationReservationRecord> = WarehouseAllocationReservationsTable.selectAll()
        .where { WarehouseAllocationReservationsTable.planId eq planId }
        .orderBy(WarehouseAllocationReservationsTable.reservationId to SortOrder.ASC)
        .map { row ->
            WarehouseAllocationReservationRecord(
                row[WarehouseAllocationReservationsTable.reservationId], row[WarehouseAllocationReservationsTable.planId],
                row[WarehouseAllocationReservationsTable.orderLineId], row[WarehouseAllocationReservationsTable.warehouseId],
                row[WarehouseAllocationReservationsTable.sku], row[WarehouseAllocationReservationsTable.quantity],
                row[WarehouseAllocationReservationsTable.state], row[WarehouseAllocationReservationsTable.revision],
            )
        }

    fun reserveStockCas(warehouseId: String, sku: String, quantity: Int, expectedRevision: Long): Boolean {
        val current = WarehouseAllocationStockTable.selectAll()
            .where { (WarehouseAllocationStockTable.warehouseId eq warehouseId) and (WarehouseAllocationStockTable.sku eq sku) }
            .forUpdate().singleOrNull() ?: return false
        if (current[WarehouseAllocationStockTable.revision] != expectedRevision ||
            current[WarehouseAllocationStockTable.onHandQuantity] - current[WarehouseAllocationStockTable.reservedQuantity] < quantity
        ) return false
        val nextReserved = current[WarehouseAllocationStockTable.reservedQuantity] + quantity
        val nextRevision = expectedRevision + 1
        val currentSnapshot = codec.decode(current[WarehouseAllocationStockTable.payload], SkuStockSnapshot::class.java)
        val nextSnapshot = currentSnapshot.copy(reservedQuantity = nextReserved, stockRevision = nextRevision)
        val updated = WarehouseAllocationStockTable.update(
            where = {
                (WarehouseAllocationStockTable.warehouseId eq warehouseId) and
                    (WarehouseAllocationStockTable.sku eq sku) and
                    (WarehouseAllocationStockTable.revision eq expectedRevision)
            },
        ) { statement ->
            statement[reservedQuantity] = nextReserved
            statement[revision] = nextRevision
            statement[payload] = codec.encode(nextSnapshot)
            statement[updatedAt] = clock.instant()
        }
        return updated == 1
    }

    fun releaseReservation(reservationId: String, expectedRevision: Long): Boolean {
        val row = WarehouseAllocationReservationsTable.selectAll()
            .where { WarehouseAllocationReservationsTable.reservationId eq reservationId }
            .forUpdate()
            .singleOrNull() ?: return false
        val state = row[WarehouseAllocationReservationsTable.state]
        if (state == ReservationState.RELEASED || state == ReservationState.CANCELLED) return true
        if (state != ReservationState.ACCEPTED || row[WarehouseAllocationReservationsTable.revision] != expectedRevision) return false
        val stock = WarehouseAllocationStockTable.selectAll().where {
            (WarehouseAllocationStockTable.warehouseId eq row[WarehouseAllocationReservationsTable.warehouseId]) and
                (WarehouseAllocationStockTable.sku eq row[WarehouseAllocationReservationsTable.sku])
        }.forUpdate().singleOrNull() ?: return false
        val current = codec.decode(stock[WarehouseAllocationStockTable.payload], SkuStockSnapshot::class.java)
        val nextRevision = stock[WarehouseAllocationStockTable.revision] + 1
        val next = current.copy(
            reservedQuantity = (current.reservedQuantity - row[WarehouseAllocationReservationsTable.quantity]).coerceAtLeast(0),
            stockRevision = nextRevision,
        )
        WarehouseAllocationStockTable.update({ WarehouseAllocationStockTable.id eq stock[WarehouseAllocationStockTable.id] }) { statement ->
            statement[reservedQuantity] = next.reservedQuantity
            statement[revision] = nextRevision
            statement[payload] = codec.encode(next)
            statement[updatedAt] = clock.instant()
        }
        return WarehouseAllocationReservationsTable.update({
            (WarehouseAllocationReservationsTable.id eq row[WarehouseAllocationReservationsTable.id]) and
                (WarehouseAllocationReservationsTable.revision eq expectedRevision)
        }) { statement ->
            statement[WarehouseAllocationReservationsTable.state] = ReservationState.RELEASED
            statement[revision] = expectedRevision + 1
            statement[updatedAt] = clock.instant()
        } == 1
    }

    fun releaseReservationsForLine(orderLineId: String): Int {
        val rows = WarehouseAllocationReservationsTable.selectAll()
            .where {
                (WarehouseAllocationReservationsTable.orderLineId eq orderLineId) and
                    (WarehouseAllocationReservationsTable.state eq ReservationState.ACCEPTED)
            }.forUpdate().toList()
        var released = 0
        rows.forEach { row ->
            val stock = WarehouseAllocationStockTable.selectAll()
                .where {
                    (WarehouseAllocationStockTable.warehouseId eq row[WarehouseAllocationReservationsTable.warehouseId]) and
                        (WarehouseAllocationStockTable.sku eq row[WarehouseAllocationReservationsTable.sku])
                }.forUpdate().singleOrNull()
            if (stock != null) {
                val current = codec.decode(stock[WarehouseAllocationStockTable.payload], SkuStockSnapshot::class.java)
                val nextRevision = stock[WarehouseAllocationStockTable.revision] + 1
                val next = current.copy(reservedQuantity = (current.reservedQuantity - row[WarehouseAllocationReservationsTable.quantity]).coerceAtLeast(0), stockRevision = nextRevision)
                WarehouseAllocationStockTable.update({ WarehouseAllocationStockTable.id eq stock[WarehouseAllocationStockTable.id] }) { statement ->
                    statement[WarehouseAllocationStockTable.reservedQuantity] = next.reservedQuantity
                    statement[WarehouseAllocationStockTable.revision] = nextRevision
                    statement[WarehouseAllocationStockTable.payload] = codec.encode(next)
                    statement[WarehouseAllocationStockTable.updatedAt] = clock.instant()
                }
            }
            val changed = WarehouseAllocationReservationsTable.update({
                (WarehouseAllocationReservationsTable.id eq row[WarehouseAllocationReservationsTable.id]) and
                    (WarehouseAllocationReservationsTable.state eq ReservationState.ACCEPTED)
            }) { statement ->
                statement[state] = ReservationState.RELEASED
                statement[revision] = row[WarehouseAllocationReservationsTable.revision] + 1
                statement[updatedAt] = clock.instant()
            }
            released += changed
        }
        return released
    }

    fun rejectReservation(reservationId: String, orderLineId: String): Boolean {
        val row = WarehouseAllocationReservationsTable.selectAll().where {
            (WarehouseAllocationReservationsTable.reservationId eq reservationId) and
                (WarehouseAllocationReservationsTable.orderLineId eq orderLineId)
        }.forUpdate().singleOrNull() ?: return false
        if (row[WarehouseAllocationReservationsTable.state] == ReservationState.REJECTED) return true
        if (row[WarehouseAllocationReservationsTable.state] == ReservationState.ACCEPTED) {
            if (!releaseReservation(reservationId, row[WarehouseAllocationReservationsTable.revision])) return false
        }
        val updated = WarehouseAllocationReservationsTable.update({ WarehouseAllocationReservationsTable.id eq row[WarehouseAllocationReservationsTable.id] }) { statement ->
            statement[WarehouseAllocationReservationsTable.state] = ReservationState.REJECTED
            statement[revision] = row[WarehouseAllocationReservationsTable.revision] + 1
            statement[updatedAt] = clock.instant()
        } == 1
        if (updated) {
            val remaining = WarehouseAllocationReservationsTable.selectAll().where {
                (WarehouseAllocationReservationsTable.orderLineId eq orderLineId) and
                    (WarehouseAllocationReservationsTable.state eq ReservationState.ACCEPTED)
            }.count()
            if (remaining == 0L) {
                val line = findOrderLine(orderLineId)
                val activePlan = activePlanId(orderLineId)
                if (line != null && activePlan != null) clearActivePlan(orderLineId, line.revision, activePlan)
            }
        }
        return updated
    }

    fun appendEvent(event: WarehouseAllocationEvent, digest: String, payloadSummary: String): EventState {
        val eventIdRow = WarehouseAllocationEventInboxTable.selectAll().where {
            WarehouseAllocationEventInboxTable.eventId eq event.eventId.value
        }.singleOrNull()
        if (eventIdRow != null) {
            if (eventIdRow[WarehouseAllocationEventInboxTable.digest] == digest) return EventState.DUPLICATE
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.EVENT_KEY_REUSED, "event id already has another digest")
        }
        val revision = eventAtRevision(event.aggregateId, event.sourceEventRevision)
        if (revision != null && revision.digest != digest) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.EVENT_REVISION_CONFLICT, "event revision already has another digest")
        }
        if (revision != null) return EventState.DUPLICATE
        val existing = WarehouseAllocationEventInboxTable.selectAll().where {
            (WarehouseAllocationEventInboxTable.aggregateId eq event.aggregateId) and
                (WarehouseAllocationEventInboxTable.eventKey eq event.eventKey.value)
        }.singleOrNull()
        if (existing != null) {
            val stored = existing[WarehouseAllocationEventInboxTable.digest]
            if (stored == digest) return EventState.DUPLICATE
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.EVENT_KEY_REUSED, "event key already has another digest")
        }
        WarehouseAllocationEventInboxTable.insert { statement ->
            statement[eventId] = event.eventId.value
            statement[eventKey] = event.eventKey.value
            statement[aggregateId] = event.aggregateId
            statement[sourceRevision] = event.sourceEventRevision
            statement[WarehouseAllocationEventInboxTable.digest] = digest
            statement[state] = EventState.ACCEPTED
            statement[WarehouseAllocationEventInboxTable.payloadSummary] = payloadSummary.take(240)
            statement[createdAt] = clock.instant()
        }
        return EventState.ACCEPTED
    }

    fun maxEventRevision(aggregateId: String): Long = WarehouseAllocationEventInboxTable.selectAll()
        .where { WarehouseAllocationEventInboxTable.aggregateId eq aggregateId }
        .maxOfOrNull { it[WarehouseAllocationEventInboxTable.sourceRevision] } ?: -1L

    fun eventAtRevision(aggregateId: String, sourceRevision: Long): WarehouseAllocationEventRecord? =
        WarehouseAllocationEventInboxTable.selectAll().where {
            (WarehouseAllocationEventInboxTable.aggregateId eq aggregateId) and
                (WarehouseAllocationEventInboxTable.sourceRevision eq sourceRevision)
        }.singleOrNull()?.let { row ->
            WarehouseAllocationEventRecord(
                row[WarehouseAllocationEventInboxTable.eventId],
                row[WarehouseAllocationEventInboxTable.eventKey],
                row[WarehouseAllocationEventInboxTable.aggregateId],
                row[WarehouseAllocationEventInboxTable.sourceRevision],
                row[WarehouseAllocationEventInboxTable.digest],
                row[WarehouseAllocationEventInboxTable.state],
                row[WarehouseAllocationEventInboxTable.payloadSummary],
                row[WarehouseAllocationEventInboxTable.createdAt],
            )
        }

    fun appendAudit(requestId: String, aggregateType: String, aggregateId: String, decision: String, summary: String, createdBy: String = "system") {
        WarehouseAllocationAuditsTable.insert { statement ->
            statement[WarehouseAllocationAuditsTable.requestId] = requestId.take(128)
            statement[WarehouseAllocationAuditsTable.aggregateType] = aggregateType.take(64)
            statement[WarehouseAllocationAuditsTable.aggregateId] = aggregateId.take(WarehouseAllocationLimits.MAX_IDENTIFIER)
            statement[WarehouseAllocationAuditsTable.decision] = decision.take(64)
            statement[WarehouseAllocationAuditsTable.summary] = summary.take(240)
            statement[WarehouseAllocationAuditsTable.createdBy] = createdBy.take(WarehouseAllocationLimits.MAX_IDENTIFIER)
            statement[WarehouseAllocationAuditsTable.createdAt] = clock.instant()
        }
    }

    fun applyInventoryAdjustment(warehouseId: String, sku: String, onHandQuantity: Int, sourceRevision: Long): Boolean {
        val row = WarehouseAllocationStockTable.selectAll()
            .where { (WarehouseAllocationStockTable.warehouseId eq warehouseId) and (WarehouseAllocationStockTable.sku eq sku) }
            .forUpdate().singleOrNull() ?: return false
        if (sourceRevision <= row[WarehouseAllocationStockTable.sourceEventRevision]) return false
        if (onHandQuantity < row[WarehouseAllocationStockTable.reservedQuantity]) return false
        val current = codec.decode(row[WarehouseAllocationStockTable.payload], SkuStockSnapshot::class.java)
        val next = current.copy(
            onHandQuantity = onHandQuantity,
            sourceEventRevision = sourceRevision,
            stockRevision = row[WarehouseAllocationStockTable.revision] + 1,
        )
        return WarehouseAllocationStockTable.update({
            (WarehouseAllocationStockTable.warehouseId eq warehouseId) and
                (WarehouseAllocationStockTable.sku eq sku) and
                (WarehouseAllocationStockTable.sourceEventRevision less sourceRevision)
        }) { statement ->
            statement[WarehouseAllocationStockTable.onHandQuantity] = onHandQuantity
            statement[WarehouseAllocationStockTable.revision] = next.stockRevision
            statement[WarehouseAllocationStockTable.sourceEventRevision] = sourceRevision
            statement[WarehouseAllocationStockTable.payload] = codec.encode(next)
            statement[WarehouseAllocationStockTable.updatedAt] = clock.instant()
        } == 1
    }

    fun claimIdempotency(
        method: String,
        route: String,
        demoScope: String,
        key: String,
        fingerprint: String,
        target: String,
        now: Instant = clock.instant(),
    ): WarehouseAllocationIdempotencyRecord? {
        val inserted = WarehouseAllocationIdempotencyTable.insertIgnore { statement ->
            statement[httpMethod] = method
            statement[routeTemplate] = route
            statement[WarehouseAllocationIdempotencyTable.demoScope] = demoScope
            statement[idempotencyKey] = key
            statement[WarehouseAllocationIdempotencyTable.fingerprint] = fingerprint
            statement[WarehouseAllocationIdempotencyTable.target] = target
            statement[status] = "IN_PROGRESS"
            statement[attempt] = 0
            statement[createdAt] = now
            statement[updatedAt] = now
        }
        val row = WarehouseAllocationIdempotencyTable.selectAll().where {
            (WarehouseAllocationIdempotencyTable.httpMethod eq method) and
                (WarehouseAllocationIdempotencyTable.routeTemplate eq route) and
                (WarehouseAllocationIdempotencyTable.demoScope eq demoScope) and
                (WarehouseAllocationIdempotencyTable.idempotencyKey eq key)
        }.single()
        return WarehouseAllocationIdempotencyRecord(
            row[WarehouseAllocationIdempotencyTable.id], method, route, demoScope, key,
            row[WarehouseAllocationIdempotencyTable.fingerprint], row[WarehouseAllocationIdempotencyTable.target],
            row[WarehouseAllocationIdempotencyTable.status], row[WarehouseAllocationIdempotencyTable.operationKey],
            row[WarehouseAllocationIdempotencyTable.response], row[WarehouseAllocationIdempotencyTable.attempt],
            row[WarehouseAllocationIdempotencyTable.nextRetryAt],
        ).also { if (inserted.insertedCount == 0 && it.fingerprint != fingerprint) throw WarehouseAllocationException(WarehouseAllocationErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT, "idempotency fingerprint conflict") }
    }

    fun updateIdempotency(id: Long, status: String, response: String?, operationKey: String? = null, attempt: Int? = null, nextRetryAt: Instant? = null) {
        WarehouseAllocationIdempotencyTable.update({ WarehouseAllocationIdempotencyTable.id eq id }) { statement ->
            statement[WarehouseAllocationIdempotencyTable.status] = status
            statement[WarehouseAllocationIdempotencyTable.response] = response
            statement[WarehouseAllocationIdempotencyTable.operationKey] = operationKey
            if (attempt != null) statement[WarehouseAllocationIdempotencyTable.attempt] = attempt
            statement[WarehouseAllocationIdempotencyTable.nextRetryAt] = nextRetryAt
            statement[updatedAt] = clock.instant()
        }
    }

    fun enqueueOutbox(operationKey: String, effectKey: String, aggregateId: String, aggregateRevision: Long, payload: String): WarehouseAllocationOutboxRecord {
        val now = clock.instant()
        WarehouseAllocationOutboxTable.insert { statement ->
            statement[WarehouseAllocationOutboxTable.operationKey] = operationKey
            statement[WarehouseAllocationOutboxTable.effectKey] = effectKey
            statement[WarehouseAllocationOutboxTable.aggregateId] = aggregateId
            statement[WarehouseAllocationOutboxTable.aggregateRevision] = aggregateRevision
            statement[status] = OutboxState.PENDING
            statement[attempt] = 0
            statement[maxAttempts] = 5
            statement[nextAttemptAt] = now
            statement[fencingToken] = 0
            statement[deliveryAttempted] = false
            statement[WarehouseAllocationOutboxTable.payload] = payload
            statement[createdAt] = now
            statement[updatedAt] = now
        }
        return outbox(operationKey)!!
    }

    fun outbox(operationKey: String): WarehouseAllocationOutboxRecord? = WarehouseAllocationOutboxTable.selectAll()
        .where { WarehouseAllocationOutboxTable.operationKey eq operationKey }.singleOrNull()?.let { row ->
            WarehouseAllocationOutboxRecord(
                row[WarehouseAllocationOutboxTable.id], row[WarehouseAllocationOutboxTable.operationKey], row[WarehouseAllocationOutboxTable.effectKey],
                row[WarehouseAllocationOutboxTable.aggregateId], row[WarehouseAllocationOutboxTable.aggregateRevision], row[WarehouseAllocationOutboxTable.status],
                row[WarehouseAllocationOutboxTable.attempt], row[WarehouseAllocationOutboxTable.maxAttempts], row[WarehouseAllocationOutboxTable.nextAttemptAt],
                row[WarehouseAllocationOutboxTable.leaseOwner], row[WarehouseAllocationOutboxTable.leaseToken], row[WarehouseAllocationOutboxTable.leaseExpiresAt],
                row[WarehouseAllocationOutboxTable.fencingToken], row[WarehouseAllocationOutboxTable.deliveryAttempted], row[WarehouseAllocationOutboxTable.payload],
            )
        }

    fun claimOutbox(operationKey: String, owner: String, token: String, now: Instant = clock.instant(), lease: Duration = Duration.ofSeconds(15)): Boolean {
        val row = outbox(operationKey) ?: return false
        if (row.status !in setOf(OutboxState.PENDING, OutboxState.RETRYABLE, OutboxState.DELIVERY_UNKNOWN)) return false
        if (row.nextAttemptAt.isAfter(now)) return false
        val effect = WarehouseAllocationOutboxEffectsTable.selectAll().where {
            (WarehouseAllocationOutboxEffectsTable.operationKey eq operationKey) and
                (WarehouseAllocationOutboxEffectsTable.effectKey eq row.effectKey)
        }.singleOrNull()
        if (effect != null && effect[WarehouseAllocationOutboxEffectsTable.state] == EffectState.COMPLETED) return false
        val updated = WarehouseAllocationOutboxTable.update({
            (WarehouseAllocationOutboxTable.operationKey eq operationKey) and
                (WarehouseAllocationOutboxTable.status inList setOf(OutboxState.PENDING, OutboxState.RETRYABLE, OutboxState.DELIVERY_UNKNOWN))
        }) { statement ->
            statement[status] = OutboxState.CLAIMED
            statement[leaseOwner] = owner
            statement[leaseToken] = token
            statement[leaseExpiresAt] = now.plus(lease)
            statement[fencingToken] = row.fencingToken + 1
            statement[attempt] = row.attempt + 1
            statement[updatedAt] = now
        }
        if (updated != 1) return false
        if (effect == null) WarehouseAllocationOutboxEffectsTable.insert { statement ->
            statement[WarehouseAllocationOutboxEffectsTable.operationKey] = operationKey
            statement[WarehouseAllocationOutboxEffectsTable.effectKey] = row.effectKey
            statement[state] = EffectState.CLAIMED
            statement[attempt] = row.attempt + 1
            statement[nextAttemptAt] = now
            statement[leaseOwner] = owner
            statement[leaseToken] = token
            statement[leaseExpiresAt] = now.plus(lease)
            statement[fencingToken] = row.fencingToken + 1
            statement[deliveryAttempted] = false
            statement[updatedAt] = now
        } else {
            WarehouseAllocationOutboxEffectsTable.update({
                (WarehouseAllocationOutboxEffectsTable.operationKey eq operationKey) and
                    (WarehouseAllocationOutboxEffectsTable.effectKey eq row.effectKey)
            }) { statement ->
                statement[state] = EffectState.CLAIMED
                statement[attempt] = row.attempt + 1
                statement[nextAttemptAt] = now
                statement[leaseOwner] = owner
                statement[leaseToken] = token
                statement[leaseExpiresAt] = now.plus(lease)
                statement[fencingToken] = row.fencingToken + 1
                statement[updatedAt] = now
            }
        }
        return true
    }

    fun completeOutbox(operationKey: String, owner: String, token: String, delivered: Boolean): Boolean {
        val row = outbox(operationKey) ?: return false
        val now = clock.instant()
        val next = if (delivered) OutboxState.DELIVERED else OutboxState.DELIVERY_UNKNOWN
        val updated = WarehouseAllocationOutboxTable.update({
            (WarehouseAllocationOutboxTable.operationKey eq operationKey) and
                (WarehouseAllocationOutboxTable.status eq OutboxState.CLAIMED) and
                (WarehouseAllocationOutboxTable.leaseOwner eq owner) and
                (WarehouseAllocationOutboxTable.leaseToken eq token) and
                (WarehouseAllocationOutboxTable.leaseExpiresAt greater now)
        }) { statement ->
            statement[status] = next
            statement[deliveryAttempted] = true
            statement[leaseOwner] = null
            statement[leaseToken] = null
            statement[leaseExpiresAt] = null
            statement[updatedAt] = clock.instant()
        }
        if (updated != 1) return false
        WarehouseAllocationOutboxEffectsTable.update({
            (WarehouseAllocationOutboxEffectsTable.operationKey eq operationKey) and
                (WarehouseAllocationOutboxEffectsTable.effectKey eq row.effectKey) and
                (WarehouseAllocationOutboxEffectsTable.leaseOwner eq owner) and
                (WarehouseAllocationOutboxEffectsTable.leaseToken eq token) and
                (WarehouseAllocationOutboxEffectsTable.leaseExpiresAt greater now)
        }) { statement ->
            statement[state] = if (delivered) io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState.COMPLETED else io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState.RECONCILE_REQUIRED
            statement[deliveryAttempted] = true
            statement[leaseOwner] = null
            statement[leaseToken] = null
            statement[leaseExpiresAt] = null
            statement[updatedAt] = clock.instant()
        }
        return true
    }

    fun markRetryable(operationKey: String, owner: String, token: String): Boolean {
        val row = outbox(operationKey) ?: return false
        val now = clock.instant()
        val retryable = row.attempt < row.maxAttempts
        val next = if (retryable) OutboxState.RETRYABLE else OutboxState.DEAD_LETTER
        val delay = (1L shl (row.attempt.coerceAtMost(5) - 1).coerceAtLeast(0)).coerceAtMost(30)
        val updated = WarehouseAllocationOutboxTable.update({
            (WarehouseAllocationOutboxTable.operationKey eq operationKey) and
                (WarehouseAllocationOutboxTable.status eq OutboxState.CLAIMED) and
                (WarehouseAllocationOutboxTable.leaseOwner eq owner) and
                (WarehouseAllocationOutboxTable.leaseToken eq token) and
                (WarehouseAllocationOutboxTable.leaseExpiresAt greater now)
        }) { statement ->
            statement[status] = next
            statement[nextAttemptAt] = now.plusSeconds(delay)
            statement[leaseOwner] = null
            statement[leaseToken] = null
            statement[leaseExpiresAt] = null
            statement[updatedAt] = clock.instant()
        }
        if (updated != 1) return false
        WarehouseAllocationOutboxEffectsTable.update({
            (WarehouseAllocationOutboxEffectsTable.operationKey eq operationKey) and
                (WarehouseAllocationOutboxEffectsTable.effectKey eq row.effectKey)
        }) { statement ->
            statement[state] = if (retryable) io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState.RETRYABLE else io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState.DEAD_LETTER
            statement[nextAttemptAt] = now.plusSeconds(delay)
            statement[leaseOwner] = null
            statement[leaseToken] = null
            statement[leaseExpiresAt] = null
            statement[updatedAt] = clock.instant()
        }
        return true
    }

    fun effect(operationKey: String): WarehouseAllocationEffectRecord? = WarehouseAllocationOutboxEffectsTable.selectAll()
        .where { WarehouseAllocationOutboxEffectsTable.operationKey eq operationKey }
        .singleOrNull()?.let { row ->
            WarehouseAllocationEffectRecord(
                row[WarehouseAllocationOutboxEffectsTable.id],
                row[WarehouseAllocationOutboxEffectsTable.operationKey],
                row[WarehouseAllocationOutboxEffectsTable.effectKey],
                row[WarehouseAllocationOutboxEffectsTable.state],
                row[WarehouseAllocationOutboxEffectsTable.attempt],
                row[WarehouseAllocationOutboxEffectsTable.nextAttemptAt],
                row[WarehouseAllocationOutboxEffectsTable.leaseOwner],
                row[WarehouseAllocationOutboxEffectsTable.leaseToken],
                row[WarehouseAllocationOutboxEffectsTable.leaseExpiresAt],
                row[WarehouseAllocationOutboxEffectsTable.fencingToken],
                row[WarehouseAllocationOutboxEffectsTable.deliveryAttempted],
            )
        }

    fun redriveOutbox(operationKey: String): Boolean {
        val row = outbox(operationKey) ?: return false
        if (row.status != OutboxState.DEAD_LETTER) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.OUTBOX_NOT_REDRIVABLE, "outbox is not dead-lettered")
        }
        val effect = effect(operationKey)
        if (effect == null || effect.state != EffectState.DEAD_LETTER) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.OUTBOX_NOT_REDRIVABLE, "outbox effect is not dead-lettered")
        }
        val updated = WarehouseAllocationOutboxTable.update({
            (WarehouseAllocationOutboxTable.operationKey eq operationKey) and
                (WarehouseAllocationOutboxTable.status eq OutboxState.DEAD_LETTER)
        }) { statement ->
            statement[status] = OutboxState.RETRYABLE
            statement[attempt] = 0
            statement[nextAttemptAt] = clock.instant()
            statement[leaseOwner] = null
            statement[leaseToken] = null
            statement[leaseExpiresAt] = null
            statement[updatedAt] = clock.instant()
        }
        if (updated != 1) return false
        WarehouseAllocationOutboxEffectsTable.update({
            (WarehouseAllocationOutboxEffectsTable.operationKey eq operationKey) and
                (WarehouseAllocationOutboxEffectsTable.state eq EffectState.DEAD_LETTER)
        }) { statement ->
            statement[state] = EffectState.RETRYABLE
            statement[attempt] = 0
            statement[nextAttemptAt] = clock.instant()
            statement[leaseOwner] = null
            statement[leaseToken] = null
            statement[leaseExpiresAt] = null
            statement[updatedAt] = clock.instant()
        }
        return true
    }

    fun replan(datasetId: String, generation: Long): WarehouseAllocationReplanRecord? = WarehouseAllocationReplansTable.selectAll()
        .where {
            (WarehouseAllocationReplansTable.datasetId eq datasetId) and
                (WarehouseAllocationReplansTable.generation eq generation)
        }
        .orderBy(WarehouseAllocationReplansTable.updatedAt to SortOrder.DESC)
        .limit(1)
        .singleOrNull()?.let { row ->
            WarehouseAllocationReplanRecord(
                row[WarehouseAllocationReplansTable.generation],
                row[WarehouseAllocationReplansTable.datasetId],
                row[WarehouseAllocationReplansTable.state],
                row[WarehouseAllocationReplansTable.staleReason],
                row[WarehouseAllocationReplansTable.planId],
                row[WarehouseAllocationReplansTable.requestId],
                row[WarehouseAllocationReplansTable.maxRevision],
            )
        }

    fun replan(generation: Long): WarehouseAllocationReplanRecord? = WarehouseAllocationReplansTable.selectAll()
        .where { WarehouseAllocationReplansTable.generation eq generation }
        .orderBy(WarehouseAllocationReplansTable.updatedAt to SortOrder.DESC)
        .limit(1)
        .singleOrNull()?.let { row ->
            WarehouseAllocationReplanRecord(
                row[WarehouseAllocationReplansTable.generation],
                row[WarehouseAllocationReplansTable.datasetId],
                row[WarehouseAllocationReplansTable.state],
                row[WarehouseAllocationReplansTable.staleReason],
                row[WarehouseAllocationReplansTable.planId],
                row[WarehouseAllocationReplansTable.requestId],
                row[WarehouseAllocationReplansTable.maxRevision],
            )
        }

    fun insertReplan(datasetId: String, generation: Long, requestId: String): Boolean {
        val now = clock.instant()
        return WarehouseAllocationReplansTable.insertIgnore { statement ->
            statement[WarehouseAllocationReplansTable.generation] = generation
            statement[WarehouseAllocationReplansTable.datasetId] = datasetId
            statement[state] = "QUEUED"
            statement[WarehouseAllocationReplansTable.requestId] = requestId
            statement[maxRevision] = 0
            statement[createdAt] = now
            statement[updatedAt] = now
        }.insertedCount == 1
    }

    fun markReplanMaterialized(datasetId: String, generation: Long, planId: String, maxRevision: Long): Boolean =
        WarehouseAllocationReplansTable.update({
            (WarehouseAllocationReplansTable.datasetId eq datasetId) and
                (WarehouseAllocationReplansTable.generation eq generation) and
                (WarehouseAllocationReplansTable.state inList listOf("QUEUED", "RUNNING"))
        }) { statement ->
            statement[WarehouseAllocationReplansTable.state] = "SUCCEEDED"
            statement[WarehouseAllocationReplansTable.planId] = planId.take(WarehouseAllocationLimits.MAX_IDENTIFIER)
            statement[WarehouseAllocationReplansTable.maxRevision] = maxRevision
            statement[WarehouseAllocationReplansTable.updatedAt] = clock.instant()
        } == 1

    fun nextReplanGeneration(datasetId: String): Long =
        (WarehouseAllocationReplansTable.selectAll()
            .where { WarehouseAllocationReplansTable.datasetId eq datasetId }
            .map { it[WarehouseAllocationReplansTable.generation] }
            .maxOrNull() ?: -1L) + 1L
}
