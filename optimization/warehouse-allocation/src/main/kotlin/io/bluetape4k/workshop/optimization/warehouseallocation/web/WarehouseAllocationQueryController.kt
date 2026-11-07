package io.bluetape4k.workshop.optimization.warehouseallocation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationReplanService
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets.UTF_8
import java.util.*

@RestController
@RequestMapping("/api/warehouse-allocation")
internal class WarehouseAllocationQueryController(
    private val repository: WarehouseAllocationRepository,
    private val replans: WarehouseAllocationReplanService,
) {

    companion object: KLogging()

    @GetMapping("/stock")
    fun stock(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): WarehouseAllocationListResponse<WarehouseAllocationStockDto> = transaction {
        val page = page(limit, cursor)
        val all = repository.listStock(10000)
        val items = all.drop(page.offset).take(page.limit)

        WarehouseAllocationListResponse(
            items = items.map {
                WarehouseAllocationStockDto(
                    warehouseId = it.warehouseId.value,
                    sku = it.sku.value,
                    availableQuantity = it.availableQuantity,
                    sourceRevision = it.sourceEventRevision
                )
            },
            nextCursor = nextCursor(page.offset, items.size, all.size)
        )
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
            orderId = value.orderId.value,
            status = value.status.name,
            revision = value.revision,
            lines = lines.map { line ->
                WarehouseAllocationOrderLineDto(
                    lineId = line.orderLineId.value,
                    status = line.status.name,
                    sku = line.sku.value,
                    requestedQuantity = line.requestedQuantity,
                    activePlanId = repository.activePlanId(line.orderLineId.value),
                    pinRevision = repository.activePin(line.orderLineId.value)?.pinRevision,
                    reservations = repository.reservations(line.orderLineId.value)
                        .map {
                            WarehouseAllocationReservationDto(it.reservationId, it.state.name)
                        },
                )
            },
            nextCursor = nextCursor(page.offset, lines.size, value.lines.size),
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
        val allocations = value.allocations
            .map { allocation ->
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
            }
            .sortedWith(compareBy({ it.lineId }, { it.warehouseId }, { it.waveId }))

        val reasons = value.unassignedReasons.entries
            .sortedBy { it.key.value }
            .map { (lineId, code) ->
                WarehouseAllocationPlanReasonDto(
                    lineId = lineId.value,
                    code = code.name,
                    affectedQuantity = repository.findOrderLine(lineId.value)?.requestedQuantity ?: 0
                )
            }

        val pageAllocations = allocations.drop(page.offset).take(page.limit)
        val pageReasons = reasons.drop(page.offset).take(page.limit)
        val history = listOf(WarehouseAllocationPlanHistoryDto(value.planRevision, value.status, requestId = "system"))

        WarehouseAllocationPlanDto(
            planId = value.planId.value,
            status = value.status,
            datasetVersion = value.datasetVersion,
            score = WarehouseAllocationScoreDto(value.hardScore, value.mediumScore, value.softScore),
            allocations = pageAllocations,
            reasons = pageReasons,
            history = history,
            nextCursor = nextCursor(
                page.offset,
                maxOf(pageAllocations.size, pageReasons.size),
                maxOf(allocations.size, reasons.size)
            ),
        )
    }

    @GetMapping("/outbox/{operationKey}")
    fun outbox(
        @PathVariable operationKey: String,
        @RequestParam(defaultValue = "request") requestId: String,
    ): WarehouseAllocationOutboxDto = transaction {
        val value = repository.outbox(operationKey)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown operation")

        val effect = repository.effect(operationKey)
        val effectState = if (value.status == OutboxState.PENDING) null else effect?.state

        val canAttempt = value.status in setOf(OutboxState.PENDING, OutboxState.CLAIMED, OutboxState.RETRYABLE)
        val nextAttemptAt = if (canAttempt) value.nextAttemptAt else null

        WarehouseAllocationOutboxDto(
            operationKey = operationKey,
            outboxState = value.status,
            effectState = effectState,
            attempt = value.attempt,
            nextAttemptAt = nextAttemptAt,
            reconciliationRequired = value.status == OutboxState.DELIVERY_UNKNOWN || effect?.state == EffectState.RECONCILE_REQUIRED,
            redriveAllowed = value.status == OutboxState.DEAD_LETTER && effect?.state == EffectState.DEAD_LETTER,
            requestId = requestId
        )
    }

    @GetMapping("/replans/{generation}")
    fun replan(@PathVariable generation: Long): WarehouseAllocationReplanDto {
        val value = replans.find(generation)
            ?: throw WarehouseAllocationException(
                WarehouseAllocationErrorCode.UNKNOWN_TARGET,
                "unknown replan generation $generation"
            )

        return WarehouseAllocationReplanDto(
            generation = value.generation,
            status = value.state,
            planId = value.planId,
            staleReason = value.staleReason,
            requestId = value.requestId
        )
    }

    private data class Page(val offset: Int, val limit: Int)

    private fun page(limit: Int, cursor: String?): Page {
        if (limit !in 1..100) {
            throw WarehouseAllocationException(
                WarehouseAllocationErrorCode.INVALID_REQUEST,
                "limit must be between 1 and 100"
            )
        }

        val offset = cursor?.takeIf { it.isNotBlank() }?.let {
            if (it.length > WarehouseAllocationLimits.MAX_CURSOR) {
                throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "cursor is too long")
            }
            val raw = runCatching {
                Base64.getUrlDecoder().decode(it).toString(UTF_8)
            }.getOrNull()
                ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "invalid cursor")

            raw.toIntOrNull()?.takeIf { value -> value >= 0 }
                ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "invalid cursor")
        } ?: 0

        return Page(offset, limit)
    }

    private fun nextCursor(offset: Int, pageSize: Int, total: Int): String? {
        val next = offset + pageSize
        if (pageSize == 0 || next >= total) return null

        return Base64.getUrlEncoder().withoutPadding().encodeToString(next.toString().toUtf8Bytes())
    }
}

@RestController
internal class WarehouseAllocationConsoleController {
    @GetMapping("/warehouse-allocation")
    fun console(): Map<String, Any> =
        mapOf(
            "name" to "warehouse-allocation",
            "provider" to "fake",
            "status" to "READY"
        )
}
