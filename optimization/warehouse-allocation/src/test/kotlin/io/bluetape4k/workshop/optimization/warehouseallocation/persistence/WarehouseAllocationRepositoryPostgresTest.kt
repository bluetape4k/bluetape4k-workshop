package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PickWave
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanProposal
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReplanState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReservationState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Sku
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.SkuStockSnapshot
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Warehouse
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEvent
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEventPayload
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEventType
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseAllocationRepositoryPostgresTest {

    companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private val repository = WarehouseAllocationRepository()
    private val now = Instant.parse("2026-08-24T00:00:00Z")

    @BeforeAll
    fun connect() {
        Database.connect(
            postgres.jdbcUrl,
            "org.postgresql.Driver",
            postgres.username.shouldNotBeNull(),
            postgres.password.shouldNotBeNull()
        )
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
            planId = PlanId("plan-1"),
            datasetId = DatasetId("dataset-1"),
            datasetVersion = 1,
            expectedOrderRevision = 0,
            warehouseRevision = 0,
            hardScore = 0,
            mediumScore = 0,
            softScore = 0,
            allocations = listOf(Allocation(line.orderLineId, warehouse.warehouseId, wave.waveId, 2)),
            unassignedReasons = emptyMap(),
        )

        transaction {
            repository.saveWarehouse(warehouse)
            repository.saveStock(stock)
            repository.saveOrder(order)
            repository.saveWave(wave)
            repository.savePlan(plan)
            repository.reserveStockCas("wh-1", "sku-1", 1, 0).shouldBeTrue()
            repository.reserveStockCas("wh-1", "sku-1", 1, 0).shouldBeFalse()
        }
        transaction {
            val current = repository.findStock("wh-1", "sku-1").shouldNotBeNull()
            current.reservedQuantity shouldBeEqualTo 1
            current.stockRevision shouldBeEqualTo 1
        }
        val result = WarehouseAllocationApprovalService(repository)
            .approve("plan-1", 0, "request-1")

        result.status shouldBeEqualTo PlanStatus.APPROVED

        transaction {
            val current = repository.findStock("wh-1", "sku-1").shouldNotBeNull()
            log.debug { "current=$current" }
            current.reservedQuantity shouldBeEqualTo 3
            current.availableQuantity shouldBeEqualTo 2

            repository.reservationsForPlan("plan-1").size shouldBeEqualTo 1
            repository.reservationsForPlan("plan-1").single().state shouldBeEqualTo ReservationState.ACCEPTED
            repository.findPlan("plan-1").shouldNotBeNull().status shouldBeEqualTo PlanStatus.APPROVED
            repository.findWave("wave-1").shouldNotBeNull().allocationIds shouldBeEqualTo listOf(OrderLineId("line-1"))
            repository.findWave("wave-1").shouldNotBeNull().revision shouldBeEqualTo 1
        }
    }

    @Test
    fun `event inbox keeps same key idempotent and different revision digest conflicting`() {
        val event = WarehouseAllocationEvent(
            eventId = EventId("event-1"),
            eventKey = EventKey("inventory-1"),
            eventType = WarehouseAllocationEventType.INVENTORY_ADJUSTED,
            target = EventTarget(WarehouseId("wh-1"), Sku("sku-1")),
            sourceEventRevision = 1,
            payload = WarehouseAllocationEventPayload.InventoryAdjusted(5),
        )
        transaction {
            val digest = WarehouseAllocationCodec().digest(event)
            repository.appendEvent(event, digest, "inventory") shouldBeEqualTo EventState.ACCEPTED
            repository.appendEvent(event, digest, "inventory") shouldBeEqualTo EventState.DUPLICATE

            val later = event.copy(
                eventId = EventId("event-2"),
                eventKey = EventKey("inventory-2"),
                sourceEventRevision = 1,
                payload = WarehouseAllocationEventPayload.InventoryAdjusted(6)
            )
            val error = assertFailsWith<WarehouseAllocationException> {
                repository.appendEvent(
                    event = later,
                    digest = WarehouseAllocationCodec().digest(later),
                    payloadSummary = "inventory"
                )
            }
            error.message shouldContain "another digest"
            val reusedId = event.copy(
                eventKey = EventKey("inventory-3"),
                payload = WarehouseAllocationEventPayload.InventoryAdjusted(7)
            )
            val idError = assertFailsWith<WarehouseAllocationException> {
                repository.appendEvent(
                    event = reusedId,
                    digest = WarehouseAllocationCodec().digest(reusedId),
                    payloadSummary = "inventory"
                )
            }
            idError.message shouldContain "event id"
        }
    }

    @Test
    fun `outbox claim creates paired effect and delivery unknown is reconciliation`() {
        transaction {
            repository.enqueueOutbox(
                operationKey = "op-1",
                effectKey = "effect-1",
                aggregateId = "aggregate-1",
                aggregateRevision = 1,
                payload = "{\"safe\":true}"
            )
        }
        transaction {
            repository.claimOutbox(operationKey = "op-1", owner = "worker-1", token = "token-1", now = Instant.now())
                .shouldBeTrue()

            val effect = repository.effect("op-1").shouldNotBeNull()

            log.debug { "effect=$effect" }
            effect.state.name shouldBeEqualTo "CLAIMED"

            repository.completeOutbox(
                operationKey = "op-1",
                owner = "worker-1",
                token = "token-1",
                delivered = false
            ).shouldBeTrue()

            repository.outbox("op-1")!!.status.name shouldBeEqualTo "DELIVERY_UNKNOWN"
            repository.effect("op-1")!!.state.name shouldBeEqualTo "RECONCILE_REQUIRED"
        }
    }

    @Test
    fun `inventory events are duplicate safe and stale revisions do not rewind stock`() {
        transaction {
            repository.saveStock(
                SkuStockSnapshot(
                    warehouseId = WarehouseId("wh-1"),
                    sku = Sku("sku-1"),
                    onHandQuantity = 2,
                    updatedAt = now
                )
            )
        }
        val service = WarehouseAllocationEventService(repository)
        val accepted = WarehouseAllocationEvent(
            eventId = EventId("event-10"),
            eventKey = EventKey("inventory-10"),
            eventType = WarehouseAllocationEventType.INVENTORY_ADJUSTED,
            target = EventTarget(WarehouseId("wh-1"), Sku("sku-1")),
            sourceEventRevision = 1,
            payload = WarehouseAllocationEventPayload.InventoryAdjusted(8),
        )
        service.ingest(accepted, "request-10").state shouldBeEqualTo EventState.ACCEPTED
        service.ingest(accepted, "request-11").state shouldBeEqualTo EventState.DUPLICATE

        val stale = accepted.copy(
            eventId = EventId("event-09"),
            eventKey = EventKey("inventory-09"),
            sourceEventRevision = 0,
            payload = WarehouseAllocationEventPayload.InventoryAdjusted(1)
        )
        assertFailsWith<WarehouseAllocationException> {
            service.ingest(stale, "request-12")
        }
        transaction {
            repository.findStock("wh-1", "sku-1")!!.onHandQuantity shouldBeEqualTo 8
        }
    }

    @Test
    fun `cancellation releases accepted reservations and projects parent order`() {
        val line = OrderLine(
            orderLineId = OrderLineId("line-cancel"),
            sku = Sku("sku-cancel"),
            requestedQuantity = 2,
            orderId = OrderId("order-cancel")
        )
        transaction {
            repository.saveWarehouse(
                Warehouse(
                    warehouseId = WarehouseId("wh-cancel"),
                    name = "Synthetic",
                    pickerCapacity = 10
                )
            )
            repository.saveStock(
                SkuStockSnapshot(
                    warehouseId = WarehouseId("wh-cancel"),
                    sku = Sku("sku-cancel"),
                    onHandQuantity = 5,
                    updatedAt = now
                )
            )
            repository.saveOrder(
                Order(
                    orderId = OrderId("order-cancel"),
                    lines = listOf(line)
                )
            )
            repository.saveWave(
                PickWave(
                    waveId = WaveId("wave-cancel"),
                    warehouseId = WarehouseId("wh-cancel"),
                    cutoffAt = now,
                    maxLines = 10
                )
            )
            repository.savePlan(
                PlanProposal(
                    planId = PlanId("plan-cancel"),
                    datasetId = DatasetId("dataset-cancel"),
                    datasetVersion = 1,
                    expectedOrderRevision = 0,
                    warehouseRevision = 0,
                    hardScore = 0,
                    mediumScore = 0,
                    softScore = 0,
                    allocations = listOf(
                        Allocation(
                            line.orderLineId,
                            WarehouseId("wh-cancel"),
                            WaveId("wave-cancel"),
                            2
                        )
                    ),
                    unassignedReasons = emptyMap(),
                )
            )
        }

        WarehouseAllocationApprovalService(repository)
            .approve(
                planId = "plan-cancel",
                expectedPlanRevision = 0,
                requestId = "request-approve-cancel"
            )
        val currentLineRevision = transaction {
            repository.findOrderLine("line-cancel")!!.revision
        }
        val event = WarehouseAllocationEvent(
            eventId = EventId("event-cancel"),
            eventKey = EventKey("cancel-1"),
            eventType = WarehouseAllocationEventType.ORDER_CANCELLED,
            target = EventTarget(orderLineId = OrderLineId("line-cancel")),
            sourceEventRevision = 1,
            payload = WarehouseAllocationEventPayload.OrderCancelled(currentLineRevision),
        )
        WarehouseAllocationEventService(repository).ingest(event, "request-cancel")

        transaction {
            repository.findStock("wh-cancel", "sku-cancel")!!.reservedQuantity shouldBeEqualTo 0
            repository.findOrder("order-cancel")!!.status shouldBeEqualTo OrderStatus.CANCELLED
            repository.findOrderLine("line-cancel")!!.status shouldBeEqualTo OrderLineStatus.CANCELLED
            repository.activePlanId("line-cancel").shouldBeNull()
            repository.reservations("line-cancel").single().state shouldBeEqualTo ReservationState.RELEASED
        }
    }

    @Test
    fun `replan generation is durable and duplicate queue replays materialized plan`() {
        val service = WarehouseAllocationReplanService(repository)
        val first = service.queue("dataset-replan", 7, "request-replan-1")
        val duplicate = service.queue("dataset-replan", 7, "request-replan-2")

        log.debug { "first=$first" }
        log.debug { "duplicate=$duplicate" }

        duplicate.operationKey shouldBeEqualTo first.operationKey
        duplicate.requestId shouldBeEqualTo first.requestId

        transaction {
            repository.markReplanMaterialized(
                datasetId = "dataset-replan",
                generation = 7,
                planId = "plan-replan-7",
                maxRevision = 12
            ).shouldBeTrue()
        }

        val stored = service.find(7).shouldNotBeNull()

        log.debug { "stored=$stored" }
        stored.planId shouldBeEqualTo "plan-replan-7"
        stored.state shouldBeEqualTo ReplanState.SUCCEEDED
    }
}
