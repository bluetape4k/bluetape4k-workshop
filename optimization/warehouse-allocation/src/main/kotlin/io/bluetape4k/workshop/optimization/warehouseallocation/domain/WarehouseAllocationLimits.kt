package io.bluetape4k.workshop.optimization.warehouseallocation.domain

internal object WarehouseAllocationLimits {
    const val MAX_DATASET_ID = 96
    const val MAX_IDENTIFIER = 160
    const val MAX_EVENT_KEY = 200
    const val MAX_HISTORY = 100
    const val MAX_EXPLANATIONS = 20
    const val MAX_EXPLANATION_LENGTH = 240
    const val MAX_BODY_BYTES = 256 * 1024
    const val MAX_CURSOR = 256
    const val MAX_LINES = 500
    const val MAX_WAREHOUSES = 100
    const val MAX_WAVES = 200
    const val MAX_STOCK_ROWS = 10_000
    const val MAX_PINS = 500
    const val MAX_CANDIDATES = 2_000_000L
    const val MAX_OUTPUT = 500
    const val MAX_PLANNER_RUNNING = 2
    const val MAX_PLANNER_WAITING = 20
    const val MAX_OUTBOX_WORKERS = 4
    const val MAX_OUTBOX_BATCH = 20
    const val MAX_OUTBOX_QUEUE = 100
    const val MAX_OUTBOX_ATTEMPTS = 5
    const val MAX_QUANTITY = 1_000_000
}
