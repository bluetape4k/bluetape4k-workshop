package io.bluetape4k.workshop.optimization.warehouseallocation.web

import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationReplanService
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

@RestController
@RequestMapping("/api/warehouse-allocation")
internal class WarehouseAllocationQueryController(
    private val repository: WarehouseAllocationRepository,
    private val replans: WarehouseAllocationReplanService,
) {
    @GetMapping("/stock")
    fun stock(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): WarehouseAllocationListResponse<WarehouseAllocationStockDto> = transaction {
        val page = page(limit, cursor)
        val all = repository.listStock(10000)
        val items = all.drop(page.offset).take(page.limit)
        WarehouseAllocationListResponse(items.map { WarehouseAllocationStockDto(it.warehouseId.value, it.sku.value, it.availableQuantity, it.sourceEventRevision) }, nextCursor(page.offset, items.size, all.size))
    }

    @GetMapping("/orders/{orderId}")
    fun order(
        @PathVariable orderId: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): WarehouseAllocationOrderDto = transaction {
        val value = repository.findOrder(orderId)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown order")
        val page = page(limit, cursor)
        val lines = value.lines.drop(page.offset).take(page.limit)
        WarehouseAllocationOrderDto(
            value.orderId.value,
            value.status.name,
            value.revision,
            lines.map { line ->
                WarehouseAllocationOrderLineDto(
                    line.orderLineId.value,
                    line.status.name,
                    line.sku.value,
                    line.requestedQuantity,
                    repository.activePlanId(line.orderLineId.value),
                    repository.activePin(line.orderLineId.value)?.pinRevision,
                    repository.reservations(line.orderLineId.value).map { WarehouseAllocationReservationDto(it.reservationId, it.state.name) },
                )
            },
            nextCursor(page.offset, lines.size, value.lines.size),
        )
    }

    @GetMapping("/plans/{planId}")
    fun plan(
        @PathVariable planId: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): WarehouseAllocationPlanDto = transaction {
        val value = repository.findPlan(planId)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown plan")
        val page = page(limit, cursor)
        val allocations = value.allocations.map { allocation ->
            val line = repository.findOrderLine(allocation.orderLineId.value)
            WarehouseAllocationPlanAllocationDto(
                allocationId = "${value.planId.value}:${allocation.orderLineId.value}:${allocation.warehouseId.value}:${allocation.waveId.value}",
                lineId = allocation.orderLineId.value,
                warehouseId = allocation.warehouseId.value,
                waveId = allocation.waveId.value,
                sku = line?.sku?.value.orEmpty(),
                quantity = allocation.quantity,
                pinned = value.manualPins.any { it.orderLineId == allocation.orderLineId && it.warehouseId == allocation.warehouseId },
            )
        }.sortedWith(compareBy({ it.lineId }, { it.warehouseId }, { it.waveId }))
        val reasons = value.unassignedReasons.entries.sortedBy { it.key.value }.map { (lineId, code) ->
            WarehouseAllocationPlanReasonDto(lineId.value, code.name, repository.findOrderLine(lineId.value)?.requestedQuantity ?: 0)
        }
        val pageAllocations = allocations.drop(page.offset).take(page.limit)
        val pageReasons = reasons.drop(page.offset).take(page.limit)
        val history = listOf(WarehouseAllocationPlanHistoryDto(value.planRevision, value.status, requestId = "system"))
        WarehouseAllocationPlanDto(
            value.planId.value,
            value.status,
            value.datasetVersion,
            WarehouseAllocationScoreDto(value.hardScore, value.mediumScore, value.softScore),
            pageAllocations,
            pageReasons,
            history,
            nextCursor(page.offset, maxOf(pageAllocations.size, pageReasons.size), maxOf(allocations.size, reasons.size)),
        )
    }

    @GetMapping("/outbox/{operationKey}")
    fun outbox(@PathVariable operationKey: String, @RequestParam(defaultValue = "request") requestId: String): WarehouseAllocationOutboxDto = transaction {
        val value = repository.outbox(operationKey)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown operation")
        val effect = repository.effect(operationKey)
        val effectState = if (value.status == io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState.PENDING) null else effect?.state
        val nextAttemptAt = if (value.status in setOf(
                io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState.PENDING,
                io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState.CLAIMED,
                io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState.RETRYABLE,
            )) value.nextAttemptAt else null
        WarehouseAllocationOutboxDto(operationKey, value.status, effectState, value.attempt, nextAttemptAt, value.status == io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState.DELIVERY_UNKNOWN || effect?.state == io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState.RECONCILE_REQUIRED, value.status == io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState.DEAD_LETTER && effect?.state == io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState.DEAD_LETTER, requestId)
    }

    @GetMapping("/replans/{generation}")
    fun replan(@PathVariable generation: Long): WarehouseAllocationReplanDto {
        val value = replans.find(generation)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown replan generation $generation")
        return WarehouseAllocationReplanDto(value.generation, value.state, planId = value.planId, staleReason = value.staleReason, requestId = value.requestId)
    }

    private data class Page(val offset: Int, val limit: Int)

    private fun page(limit: Int, cursor: String?): Page {
        if (limit !in 1..100) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "limit must be between 1 and 100")
        }
        val offset = cursor?.takeIf { it.isNotBlank() }?.let {
            if (it.length > io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits.MAX_CURSOR) {
                throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "cursor is too long")
            }
            val raw = runCatching { Base64.getUrlDecoder().decode(it).toString(UTF_8) }.getOrNull()
                ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "invalid cursor")
            raw.toIntOrNull()?.takeIf { value -> value >= 0 }
                ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "invalid cursor")
        } ?: 0
        return Page(offset, limit)
    }

    private fun nextCursor(offset: Int, pageSize: Int, total: Int): String? {
        val next = offset + pageSize
        if (pageSize == 0 || next >= total) return null
        return Base64.getUrlEncoder().withoutPadding().encodeToString(next.toString().toByteArray(UTF_8))
    }
}

@RestController
internal class WarehouseAllocationConsoleController {
    @GetMapping("/warehouse-allocation")
    fun console(): Map<String, Any> = mapOf("name" to "warehouse-allocation", "provider" to "fake", "status" to "READY")
}
