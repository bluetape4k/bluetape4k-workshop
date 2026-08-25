package io.bluetape4k.workshop.optimization.warehouseallocation.domain

internal fun ShippingRule.requiredCapabilities(): Set<WarehouseCapability> = when (this) {
    ShippingRule.COLD_CHAIN -> setOf(WarehouseCapability.COLD_CHAIN)
    ShippingRule.HAZMAT -> setOf(WarehouseCapability.HAZMAT)
    ShippingRule.COLD_CHAIN_AND_HAZMAT -> setOf(WarehouseCapability.COLD_CHAIN, WarehouseCapability.HAZMAT)
    ShippingRule.STANDARD -> emptySet()
}

internal fun Warehouse.supports(rule: ShippingRule): Boolean =
    !incident && capabilities.containsAll(rule.requiredCapabilities())

internal fun PickWave.isAvailableFor(line: OrderLine, warehouse: Warehouse): Boolean =
    status == PickWaveStatus.OPEN &&
        warehouseId == warehouse.warehouseId &&
        warehouseCapacityRevision == warehouse.revision &&
        (line.carrierCutoff == null || cutoffAt <= line.carrierCutoff)
