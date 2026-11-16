package io.bluetape4k.workshop.optimization.warehouseallocation.planner

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Allocation
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.CommittedAllocationPin
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLine
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PickWave
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanProposal
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ShippingRule
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Sku
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.SkuStockSnapshot
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Warehouse
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationNextAction
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationPlannerInput
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationPlannerOutput
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationReasonCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WaveId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.isAvailableFor
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.requiredCapabilities
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.supports
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

internal class WarehouseAllocationPlanner(
    private val deadline: Duration = 2.seconds(),
    private val clock: () -> Instant = Instant::now,
) {

    companion object: KLogging()

    fun plan(input: WarehouseAllocationPlannerInput): WarehouseAllocationPlannerOutput {
        validate(input)
        val started = clock()
        val warehouseById = input.warehouses.associateBy { it.warehouseId }
        val stock = input.stocks.associateBy { it.warehouseId to it.sku }.toMutableMap()
        val waves = input.waves.sortedWith(compareBy<PickWave> { it.cutoffAt }.thenBy { it.waveId.value })

        val lineOrder = input.orders
            .flatMap { order ->
                order.lines
                    .filter { it.status == OrderLineStatus.OPEN || it.status == OrderLineStatus.PARTIALLY_ALLOCATED }
                    .map { order.orderId to it }
            }
            .sortedWith(
                compareBy(
                    { it.second.carrierCutoff ?: Instant.MAX },
                    { capabilityWeight(it.second.shippingRule) },
                    { -it.second.requestedQuantity },
                    { it.second.orderLineId.value },
                )
            )
        val pins = input.pins.filter { it.status.name == "ACTIVE" }.associateBy { it.orderLineId }
        val allocations = mutableListOf<Allocation>()
        val reasons = linkedMapOf<OrderLineId, WarehouseAllocationReasonCode>()
        val split = linkedSetOf<OrderLineId>()

        val usedWaveLines = waves
            .associate { wave -> wave.waveId to wave.allocationIds.distinct().size }
            .toMutableMap()

        lineOrder.forEach { (_, line) ->
            if (Duration.between(started, clock()) > deadline) {
                throw WarehouseAllocationException(
                    WarehouseAllocationErrorCode.PLANNER_DEADLINE_EXCEEDED,
                    "planner deadline exceeded",
                    nextAction = WarehouseAllocationNextAction.SHRINK_DATASET
                )
            }
            val pin = pins[line.orderLineId]
            if (pin != null && pin.pinRevision < line.revision) {
                reasons[line.orderLineId] = WarehouseAllocationReasonCode.PIN_STALE
                return@forEach
            }
            if (pin != null && (pin.quantity > line.requestedQuantity ||
                        (stock[pin.warehouseId to line.sku]?.availableQuantity ?: 0) < pin.quantity)) {
                reasons[line.orderLineId] = WarehouseAllocationReasonCode.PIN_CONFLICT
                return@forEach
            }
            val candidates = candidateWarehouses(input, line, pin, warehouseById, stock, waves, usedWaveLines)
            var remaining = line.requestedQuantity

            candidates.forEach { candidate ->
                if (remaining <= 0) return@forEach
                val available = stock[candidate.warehouseId to line.sku]?.availableQuantity ?: 0
                val quantity = minOf(remaining, available)
                if (quantity <= 0) return@forEach
                val currentWaveLines = usedWaveLines.getOrDefault(candidate.waveId, 0)
                if (currentWaveLines >= candidate.maxLines) return@forEach
                allocations += Allocation(line.orderLineId, candidate.warehouseId, candidate.waveId, quantity)
                usedWaveLines[candidate.waveId] = currentWaveLines + 1
                val snapshot = stock.getValue(candidate.warehouseId to line.sku)
                stock[candidate.warehouseId to line.sku] =
                    snapshot.copy(reservedQuantity = snapshot.reservedQuantity + quantity)
                remaining -= quantity
            }

            if (remaining > 0) {
                val reason = when {
                    pin != null &&
                            allocations.none {
                                it.orderLineId == line.orderLineId &&
                                        it.warehouseId == pin.warehouseId
                            }                                                        ->
                        WarehouseAllocationReasonCode.PIN_CONFLICT

                    candidates.isEmpty() &&
                            input.warehouses.none { it.supports(line.shippingRule) } ->
                        requiredReason(line.shippingRule)

                    candidates.isEmpty() &&
                            input.warehouses.any {
                                it.incident &&
                                        it.capabilities.containsAll(line.shippingRule.requiredCapabilities())
                            }                                                        ->
                        WarehouseAllocationReasonCode.WAREHOUSE_INCIDENT

                    candidates.isEmpty() &&
                            line.carrierCutoff != null &&
                            waves.none { it.cutoffAt <= line.carrierCutoff }         ->
                        WarehouseAllocationReasonCode.CARRIER_CUTOFF

                    candidates.isEmpty()                                             ->
                        WarehouseAllocationReasonCode.PICKER_CAPACITY

                    else                                                             ->
                        WarehouseAllocationReasonCode.STOCK_UNAVAILABLE
                }
                reasons[line.orderLineId] = reason
            }
            if (allocations.count { it.orderLineId == line.orderLineId } > 1) split += line.orderLineId
        }
        if (allocations.size > WarehouseAllocationLimits.MAX_OUTPUT) {
            throw WarehouseAllocationException(
                WarehouseAllocationErrorCode.PLANNER_OUTPUT_TOO_LARGE,
                "planner output too large",
                nextAction = WarehouseAllocationNextAction.SHRINK_DATASET
            )
        }
        val proposal = PlanProposal(
            planId = input.planId,
            datasetId = input.datasetId,
            datasetVersion = input.datasetVersion,
            expectedOrderRevision = input.expectedOrderRevision,
            warehouseRevision = input.warehouses.maxOfOrNull { it.revision } ?: 0,
            hardScore = -reasons.size,
            mediumScore = -split.size,
            softScore = -allocations.map { it.warehouseId.value }.distinct().size,
            allocations = allocations.toList(),
            unassignedReasons = reasons.toMap(),
            splitShipmentReasons = split.toSet(),
            manualPins = input.pins,
        )
        val digest = digest(proposal)

        return WarehouseAllocationPlannerOutput(proposal.copy(digest = digest), digest)
    }

    private fun validate(input: WarehouseAllocationPlannerInput) {
        fun requireBound(value: Boolean, message: String) {
            if (!value) throw WarehouseAllocationException(
                WarehouseAllocationErrorCode.PLANNER_INPUT_TOO_LARGE,
                message,
                nextAction = WarehouseAllocationNextAction.SHRINK_DATASET,
            )
        }
        requireBound(
            input.orders.flatMap { it.lines }.size <= WarehouseAllocationLimits.MAX_LINES,
            "planner line bound exceeded"
        )
        requireBound(
            input.warehouses.size <= WarehouseAllocationLimits.MAX_WAREHOUSES,
            "planner warehouse bound exceeded"
        )
        requireBound(input.waves.size <= WarehouseAllocationLimits.MAX_WAVES, "planner wave bound exceeded")
        requireBound(input.stocks.size <= WarehouseAllocationLimits.MAX_STOCK_ROWS, "planner stock bound exceeded")
        requireBound(input.pins.size <= WarehouseAllocationLimits.MAX_PINS, "planner pin bound exceeded")

        val candidates = input.orders.sumOf { it.lines.size }.toLong() * input.warehouses.size * input.waves.size

        if (candidates > WarehouseAllocationLimits.MAX_CANDIDATES) {
            throw WarehouseAllocationException(
                WarehouseAllocationErrorCode.PLANNER_INPUT_TOO_LARGE,
                "planner candidate budget exceeded",
                nextAction = WarehouseAllocationNextAction.SHRINK_DATASET
            )
        }
    }

    private data class Candidate(
        val warehouseId: WarehouseId,
        val waveId: WaveId,
        val maxLines: Int,
    )

    private fun candidateWarehouses(
        input: WarehouseAllocationPlannerInput,
        line: OrderLine,
        pin: CommittedAllocationPin?,
        warehouseById: Map<WarehouseId, Warehouse>,
        stock: Map<Pair<WarehouseId, Sku>, SkuStockSnapshot>,
        waves: List<PickWave>,
        usedWaveLines: Map<WaveId, Int>,
    ): List<Candidate> {
        val warehouses = input.warehouses
            .sortedBy { it.warehouseId.value }
            .filter { warehouse ->
                warehouse.supports(line.shippingRule) && (pin == null || pin.warehouseId == warehouse.warehouseId)
            }

        return warehouses
            .flatMap { warehouse ->
                waves
                    .filter { wave ->
                        wave.isAvailableFor(line, warehouse) &&
                                usedWaveLines.getOrDefault(wave.waveId, 0) < wave.maxLines &&
                                (stock[warehouse.warehouseId to line.sku]?.availableQuantity ?: 0) > 0
                    }
                    .map { Candidate(warehouse.warehouseId, it.waveId, it.maxLines) }
            }
    }

    private fun capabilityWeight(rule: ShippingRule): Int = when (rule) {
        ShippingRule.COLD_CHAIN_AND_HAZMAT -> 0
        ShippingRule.COLD_CHAIN, ShippingRule.HAZMAT -> 1
        ShippingRule.STANDARD              -> 2
    }

    private fun requiredReason(rule: ShippingRule): WarehouseAllocationReasonCode = when (rule) {
        ShippingRule.COLD_CHAIN,
        ShippingRule.COLD_CHAIN_AND_HAZMAT,
                            -> WarehouseAllocationReasonCode.COLD_CHAIN

        ShippingRule.HAZMAT -> WarehouseAllocationReasonCode.HAZMAT
        ShippingRule.STANDARD -> WarehouseAllocationReasonCode.STOCK_UNAVAILABLE
    }

    private fun digest(proposal: PlanProposal): String {
        val canonical = buildString {
            append("warehouse-canonical-v1|")
            append(proposal.planId).append('|').append(proposal.datasetId).append('|')
            append(proposal.datasetVersion).append('|').append(proposal.expectedOrderRevision).append('|')
            append(proposal.warehouseRevision).append('|')

            proposal.allocations
                .sortedWith(
                    compareBy(
                        { it.orderLineId.value },
                        { it.warehouseId.value },
                        { it.waveId.value })
                )
                .forEach {
                    append(it.orderLineId)
                    append(':')
                    append(it.warehouseId)
                    append(':')
                    append(it.waveId)
                    append(':')
                    append(it.quantity)
                    append(';')
                }
            proposal.unassignedReasons
                .toSortedMap(compareBy { it.value })
                .forEach { (line, reason) ->
                    append(line)
                    append('=')
                    append(reason)
                    append(';')
                }
            proposal.splitShipmentReasons
                .sortedBy { it.value }
                .forEach {
                    append("split=")
                    append(it)
                    append(';')
                }
            proposal.manualPins
                .sortedWith(compareBy({ it.orderLineId.value }, { it.pinRevision }))
                .forEach {
                    append("pin=")
                    append(it.orderLineId)
                    append(':')
                    append(it.warehouseId)
                    append(':')
                    append(it.quantity)
                    append(':')
                    append(it.pinRevision)
                    append(':')
                    append(it.status)
                    append(';')
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toUtf8Bytes())
            .joinToString("") { "%02x".format(it) }
    }
}
