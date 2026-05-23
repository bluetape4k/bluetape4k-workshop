package io.bluetape4k.workshop.lock.domain

import io.bluetape4k.logging.KLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory inventory store backed by a [ConcurrentHashMap].
 *
 * ## Behavior / Contract
 * - [register] overwrites any existing stock for the given inventory id.
 * - [applyChange] uses `addAndGet` (atomic), enabling intentional race demos in [UnsafeInventoryService].
 * - [resetAll] clears all registered inventories; used in `@BeforeEach` for test isolation.
 * - Accessing an unregistered id throws [IllegalArgumentException].
 */
class InventoryStore {

    companion object : KLogging()

    private val store = ConcurrentHashMap<Long, AtomicInteger>()

    fun register(inventory: Inventory) {
        store[inventory.id] = AtomicInteger(inventory.initialStock)
    }

    fun currentStock(id: Long): Int =
        (store[id] ?: throw IllegalArgumentException("Inventory $id not registered")).get()

    fun applyChange(id: Long, delta: Int): Int =
        (store[id] ?: throw IllegalArgumentException("Inventory $id not registered")).addAndGet(delta)

    fun reset(id: Long, value: Int) {
        store[id]?.set(value) ?: throw IllegalArgumentException("Inventory $id not registered")
    }

    /** Clears all registered inventories. Used for `@BeforeEach` test isolation. */
    fun resetAll() {
        store.clear()
    }
}
