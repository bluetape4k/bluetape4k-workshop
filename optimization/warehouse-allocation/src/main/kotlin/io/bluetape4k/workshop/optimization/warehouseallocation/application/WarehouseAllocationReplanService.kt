package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReplanStaleReason
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReplanState
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

    companion object: KLogging()

    fun queue(datasetId: String, generation: Long, requestId: String): WarehouseAllocationReplanResult =
        transaction {
            val inserted = repository.insertReplan(datasetId, generation, requestId)
            val record = if (inserted) null else repository.replan(datasetId, generation)

            log.debug { "record=$record" }

            if (record == null) {
                WarehouseAllocationReplanResult(
                    generation = generation,
                    state = ReplanState.QUEUED,
                    requestId = requestId,
                    operationKey = "replan-$datasetId-$generation"
                )
            } else {
                WarehouseAllocationReplanResult(
                    generation = record.generation,
                    state = runCatching { ReplanState.valueOf(record.state) }.getOrDefault(ReplanState.FAILED),
                    staleReason = record.staleReason?.let { runCatching { ReplanStaleReason.valueOf(it) }.getOrNull() },
                    requestId = record.requestId,
                    operationKey = "replan-${record.datasetId}-${record.generation}",
                    planId = record.planId,
                )
            }
        }

    fun find(generation: Long): WarehouseAllocationReplanResult? =
        transaction {
            repository.replan(generation)
                ?.let { record ->
                    WarehouseAllocationReplanResult(
                        generation = record.generation,
                        state = runCatching { ReplanState.valueOf(record.state) }.getOrDefault(ReplanState.FAILED),
                        staleReason = record.staleReason?.let { runCatching { ReplanStaleReason.valueOf(it) }.getOrNull() },
                        requestId = record.requestId,
                        operationKey = "replan-${record.datasetId}-${record.generation}",
                        planId = record.planId,
                    )
                }
        }
}
