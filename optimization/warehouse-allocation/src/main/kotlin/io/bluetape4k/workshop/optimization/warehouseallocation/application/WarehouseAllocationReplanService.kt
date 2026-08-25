package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReplanState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReplanStaleReason
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service

internal data class WarehouseAllocationReplanResult(
    val generation: Long,
    val state: ReplanState,
    val staleReason: ReplanStaleReason? = null,
    val requestId: String,
    val operationKey: String = "replan-$generation",
    val planId: String? = null,
)

@Service
internal class WarehouseAllocationReplanService(
    private val repository: WarehouseAllocationRepository,
) {
    fun queue(datasetId: String, generation: Long, requestId: String): WarehouseAllocationReplanResult {
        return transaction {
            val inserted = repository.insertReplan(datasetId, generation, requestId)
            val record = if (inserted) null else repository.replan(datasetId, generation)
            if (record == null) {
                WarehouseAllocationReplanResult(generation, ReplanState.QUEUED, requestId = requestId, operationKey = "replan-$datasetId-$generation")
            } else {
                WarehouseAllocationReplanResult(
                    record.generation,
                    runCatching { ReplanState.valueOf(record.state) }.getOrDefault(ReplanState.FAILED),
                    record.staleReason?.let { runCatching { ReplanStaleReason.valueOf(it) }.getOrNull() },
                    record.requestId,
                    "replan-${record.datasetId}-${record.generation}",
                    record.planId,
                )
            }
        }
    }

    fun find(generation: Long): WarehouseAllocationReplanResult? = transaction {
        repository.replan(generation)?.let { record ->
            WarehouseAllocationReplanResult(
                record.generation,
                runCatching { ReplanState.valueOf(record.state) }.getOrDefault(ReplanState.FAILED),
                record.staleReason?.let { runCatching { ReplanStaleReason.valueOf(it) }.getOrNull() },
                record.requestId,
                "replan-${record.datasetId}-${record.generation}",
                record.planId,
            )
        }
    }
}
