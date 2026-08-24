package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationApprovalService
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationEventService
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationReplanService
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Allocation
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.DatasetId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventKey
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventTarget
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Order
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLine
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanProposal
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PickWave
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReservationState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Sku
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.SkuStockSnapshot
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Warehouse
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEvent
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEventPayload
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEventType
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WaveId
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseAllocationRepositoryPostgresTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = WarehouseAllocationRepository()
    private val now = Instant.parse("2026-08-24T00:00:00Z")

    @BeforeAll
    fun connect() {
        Database.connect(postgres.jdbcUrl, "org.postgresql.Driver", requireNotNull(postgres.username), requireNotNull(postgres.password))
    }

    @BeforeEach
    fun createSchema() {
        transaction {
            SchemaUtils.drop(*WarehouseAllocationTables.all.reversedArray())
            SchemaUtils.create(*WarehouseAllocationTables.all)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction { SchemaUtils.drop(*WarehouseAllocationTables.all.reversedArray()) }
    }

    @Test
    fun `approval reserves stock with current revision and rejects stale CAS`() {
        val line = OrderLine(OrderLineId("line-1"), Sku("sku-1"), 2, orderId = OrderId("order-1"))
        val warehouse = Warehouse(WarehouseId("wh-1"), "Synthetic", pickerCapacity = 10)
        val stock = SkuStockSnapshot(WarehouseId("wh-1"), Sku("sku-1"), 5, updatedAt = now)
        val order = Order(OrderId("order-1"), listOf(line))
        val wave = PickWave(WaveId("wave-1"), WarehouseId("wh-1"), now, 10)
        val plan = PlanProposal(
            PlanId("plan-1"), DatasetId("dataset-1"), 1, 0, 0, 0, 0, 0,
            listOf(Allocation(line.orderLineId, warehouse.warehouseId, wave.waveId, 2)), emptyMap(),
        )
        transaction {
            repository.saveWarehouse(warehouse)
            repository.saveStock(stock)
            repository.saveOrder(order)
            repository.saveWave(wave)
            repository.savePlan(plan)
            assertTrue(repository.reserveStockCas("wh-1", "sku-1", 1, 0))
            assertFalse(repository.reserveStockCas("wh-1", "sku-1", 1, 0))
        }
        transaction {
            val current = repository.findStock("wh-1", "sku-1")!!
            assertEquals(1, current.reservedQuantity)
            assertEquals(1, current.stockRevision)
        }
        val result = WarehouseAllocationApprovalService(repository).approve("plan-1", 0, "request-1")
        assertEquals(PlanStatus.APPROVED, result.status)
        transaction {
            val current = repository.findStock("wh-1", "sku-1")!!
            assertEquals(3, current.reservedQuantity)
            assertEquals(2, current.availableQuantity)
            assertEquals(1, repository.reservationsForPlan("plan-1").size)
            assertEquals(ReservationState.ACCEPTED, repository.reservationsForPlan("plan-1").single().state)
            assertEquals(PlanStatus.APPROVED, repository.findPlan("plan-1")!!.status)
            assertEquals(listOf(OrderLineId("line-1")), repository.findWave("wave-1")!!.allocationIds)
            assertEquals(1, repository.findWave("wave-1")!!.revision)
        }
    }

    @Test
    fun `event inbox keeps same key idempotent and different revision digest conflicting`() {
        val event = WarehouseAllocationEvent(
            EventId("event-1"), EventKey("inventory-1"), WarehouseAllocationEventType.INVENTORY_ADJUSTED,
            EventTarget(WarehouseId("wh-1"), Sku("sku-1")), 1,
            WarehouseAllocationEventPayload.InventoryAdjusted(5),
        )
        transaction {
            val digest = WarehouseAllocationCodec().digest(event)
            assertEquals(EventState.ACCEPTED, repository.appendEvent(event, digest, "inventory"))
            assertEquals(EventState.DUPLICATE, repository.appendEvent(event, digest, "inventory"))
            val later = event.copy(eventId = EventId("event-2"), eventKey = EventKey("inventory-2"), sourceEventRevision = 1, payload = WarehouseAllocationEventPayload.InventoryAdjusted(6))
            val error = kotlin.runCatching { repository.appendEvent(later, WarehouseAllocationCodec().digest(later), "inventory") }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error.message!!.contains("another digest"))
            val reusedId = event.copy(eventKey = EventKey("inventory-3"), payload = WarehouseAllocationEventPayload.InventoryAdjusted(7))
            val idError = kotlin.runCatching { repository.appendEvent(reusedId, WarehouseAllocationCodec().digest(reusedId), "inventory") }.exceptionOrNull()
            assertNotNull(idError)
            assertTrue(idError.message!!.contains("event id"))
        }
    }

    @Test
    fun `outbox claim creates paired effect and delivery unknown is reconciliation`() {
        transaction { repository.enqueueOutbox("op-1", "effect-1", "aggregate-1", 1, "{\"safe\":true}") }
        transaction {
            assertTrue(repository.claimOutbox("op-1", "worker-1", "token-1", Instant.now()))
            val effect = repository.effect("op-1")
            assertNotNull(effect)
            assertEquals("CLAIMED", effect.state.name)
            assertTrue(repository.completeOutbox("op-1", "worker-1", "token-1", delivered = false))
            assertEquals("DELIVERY_UNKNOWN", repository.outbox("op-1")!!.status.name)
            assertEquals("RECONCILE_REQUIRED", repository.effect("op-1")!!.state.name)
        }
    }

    @Test
    fun `inventory events are duplicate safe and stale revisions do not rewind stock`() {
        transaction {
            repository.saveStock(SkuStockSnapshot(WarehouseId("wh-1"), Sku("sku-1"), 2, updatedAt = now))
        }
        val service = WarehouseAllocationEventService(repository)
        val accepted = WarehouseAllocationEvent(
            EventId("event-10"), EventKey("inventory-10"), WarehouseAllocationEventType.INVENTORY_ADJUSTED,
            EventTarget(WarehouseId("wh-1"), Sku("sku-1")), 1,
            WarehouseAllocationEventPayload.InventoryAdjusted(8),
        )
        assertEquals(EventState.ACCEPTED, service.ingest(accepted, "request-10").state)
        assertEquals(EventState.DUPLICATE, service.ingest(accepted, "request-11").state)
        val stale = accepted.copy(eventId = EventId("event-09"), eventKey = EventKey("inventory-09"), sourceEventRevision = 0, payload = WarehouseAllocationEventPayload.InventoryAdjusted(1))
        assertFailsWith<io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException> {
            service.ingest(stale, "request-12")
        }
        transaction { assertEquals(8, repository.findStock("wh-1", "sku-1")!!.onHandQuantity) }
    }

    @Test
    fun `cancellation releases accepted reservations and projects parent order`() {
        val line = OrderLine(OrderLineId("line-cancel"), Sku("sku-cancel"), 2, orderId = OrderId("order-cancel"))
        transaction {
            repository.saveWarehouse(Warehouse(WarehouseId("wh-cancel"), "Synthetic", pickerCapacity = 10))
            repository.saveStock(SkuStockSnapshot(WarehouseId("wh-cancel"), Sku("sku-cancel"), 5, updatedAt = now))
            repository.saveOrder(Order(OrderId("order-cancel"), listOf(line)))
            repository.saveWave(PickWave(WaveId("wave-cancel"), WarehouseId("wh-cancel"), now, 10))
            repository.savePlan(PlanProposal(
                PlanId("plan-cancel"), DatasetId("dataset-cancel"), 1, 0, 0, 0, 0, 0,
                listOf(Allocation(line.orderLineId, WarehouseId("wh-cancel"), WaveId("wave-cancel"), 2)), emptyMap(),
            ))
        }
        WarehouseAllocationApprovalService(repository).approve("plan-cancel", 0, "request-approve-cancel")
        val currentLineRevision = transaction { repository.findOrderLine("line-cancel")!!.revision }
        val event = WarehouseAllocationEvent(
            EventId("event-cancel"), EventKey("cancel-1"), WarehouseAllocationEventType.ORDER_CANCELLED,
            EventTarget(orderLineId = OrderLineId("line-cancel")), 1,
            WarehouseAllocationEventPayload.OrderCancelled(currentLineRevision),
        )
        WarehouseAllocationEventService(repository).ingest(event, "request-cancel")
        transaction {
            assertEquals(0, repository.findStock("wh-cancel", "sku-cancel")!!.reservedQuantity)
            assertEquals(io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderStatus.CANCELLED, repository.findOrder("order-cancel")!!.status)
            assertEquals(io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus.CANCELLED, repository.findOrderLine("line-cancel")!!.status)
            assertEquals(null, repository.activePlanId("line-cancel"))
            assertEquals(ReservationState.RELEASED, repository.reservations("line-cancel").single().state)
        }
    }

    @Test
    fun `replan generation is durable and duplicate queue replays materialized plan`() {
        val service = WarehouseAllocationReplanService(repository)
        val first = service.queue("dataset-replan", 7, "request-replan-1")
        val duplicate = service.queue("dataset-replan", 7, "request-replan-2")
        assertEquals(first.operationKey, duplicate.operationKey)
        assertEquals(first.requestId, duplicate.requestId)
        transaction {
            assertTrue(repository.markReplanMaterialized("dataset-replan", 7, "plan-replan-7", 12))
        }
        val stored = service.find(7)
        assertNotNull(stored)
        assertEquals("plan-replan-7", stored.planId)
        assertEquals(io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReplanState.SUCCEEDED, stored.state)
    }
}
