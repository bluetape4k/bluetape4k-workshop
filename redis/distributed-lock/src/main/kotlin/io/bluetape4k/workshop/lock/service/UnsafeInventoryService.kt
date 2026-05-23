package io.bluetape4k.workshop.lock.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.lock.domain.DeductionResult
import io.bluetape4k.workshop.lock.domain.DeductionResult.InsufficientStock
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.InventoryStore

/**
 * Intentionally unsafe inventory service — demonstrates a classic read-modify-write race.
 *
 * ## Behavior / Contract
 * - Reads stock, sleeps for 1 ms to widen the race window, then writes — **without any lock**.
 * - Multiple concurrent threads will observe over-sell (negative stock or success count > expected).
 *
 * **DO NOT use in production.**
 */
class UnsafeInventoryService(private val store: InventoryStore) {

    companion object : KLogging()

    fun deduct(id: Long, qty: Int): DeductionResult {
        qty.requirePositiveNumber("qty")
        val current = store.currentStock(id)
        if (current < qty) return InsufficientStock(qty, current)
        Thread.sleep(1)  // intentional race window
        val remaining = store.applyChange(id, -qty)
        return Success(remaining)
    }
}
