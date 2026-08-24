package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanProposal
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.DatasetId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationNextAction
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationPlannerInput
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import io.bluetape4k.workshop.optimization.warehouseallocation.planner.WarehouseAllocationPlanner
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Semaphore

internal data class WarehouseAllocationPlanCommandResult(
    val operationKey: String,
    val requestId: String,
    val proposal: PlanProposal,
    val state: String = "QUEUED",
)

internal class WarehouseAllocationPlannerAdmission(
    private val runningLimit: Int = io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits.MAX_PLANNER_RUNNING,
    private val waitingLimit: Int = io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits.MAX_PLANNER_WAITING,
) {
    private val permits = Semaphore(runningLimit, true)
    private val waiting = AtomicInteger()

    fun <T> execute(block: () -> T): T {
        if (!permits.tryAcquire()) {
            val queued = waiting.incrementAndGet()
            if (queued > waitingLimit) {
                waiting.decrementAndGet()
                throw WarehouseAllocationException(
                    WarehouseAllocationErrorCode.PLANNER_CAPACITY_EXCEEDED,
                    "planner queue is full",
                    retryable = true,
                    nextAction = WarehouseAllocationNextAction.RETRY_AFTER,
                    retryAfterSeconds = 5,
                )
            }
            try {
                permits.acquire()
            } finally {
                waiting.decrementAndGet()
            }
        }
        return try { block() } finally { permits.release() }
    }
}

@Service
internal class WarehouseAllocationCommandService(
    private val repository: WarehouseAllocationRepository,
    private val planner: WarehouseAllocationPlanner,
) {
    private val admission = WarehouseAllocationPlannerAdmission()

    fun createPlan(input: WarehouseAllocationPlannerInput, requestId: String, operationKey: String = "op-${UUID.randomUUID()}"): WarehouseAllocationPlanCommandResult {
        val output = admission.execute { planner.plan(input) }
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            repository.savePlan(output.proposal)
            repository.enqueueOutbox(operationKey, "plan-created", "warehouse-allocation:${input.datasetId}", output.proposal.planRevision, "{\"planId\":\"${output.proposal.planId}\"}")
        }
        return WarehouseAllocationPlanCommandResult(operationKey, requestId, output.proposal)
    }

    fun createPlanFromCurrentSnapshot(
        datasetId: String,
        seed: Long,
        requestId: String,
        operationKey: String = "plan-${UUID.randomUUID()}",
    ): WarehouseAllocationPlanCommandResult {
        val snapshot = transaction {
            val warehouses = repository.listWarehouses()
            val stocks = repository.listStock(WarehouseAllocationLimits.MAX_STOCK_ROWS)
            val orders = repository.listOrders()
            val waves = repository.listWaves()
            val pins = repository.listActivePins()
            val datasetVersion = listOf(
                warehouses.maxOfOrNull { it.revision } ?: 0,
                stocks.maxOfOrNull { it.stockRevision } ?: 0,
                orders.maxOfOrNull { it.revision } ?: 0,
                waves.maxOfOrNull { it.revision } ?: 0,
                pins.maxOfOrNull { it.pinRevision } ?: 0,
            ).maxOrNull() ?: 0
            WarehouseAllocationPlannerInput(
                datasetId = DatasetId(datasetId),
                datasetVersion = datasetVersion,
                expectedOrderRevision = orders.minOfOrNull { it.revision } ?: 0,
                warehouses = warehouses,
                stocks = stocks,
                orders = orders,
                waves = waves,
                pins = pins,
                planId = PlanId("plan-${datasetId.take(80)}-$datasetVersion-$seed"),
                seed = seed,
            )
        }
        return createPlan(snapshot, requestId, operationKey)
    }
}
