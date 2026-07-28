package io.bluetape4k.workshop.lock.domain

import io.bluetape4k.logging.KLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ConcurrentHashMap] 기반 in-memory inventory store 입니다.
 *
 * ## Behavior / Contract
 * - [register] 는 주어진 inventory id 의 기존 stock 을 덮어씁니다.
 * - [applyChange] 는 `addAndGet`(atomic)을 사용하므로 [UnsafeInventoryService] 에서 의도적인 race demo 가 가능합니다.
 * - [resetAll] 은 등록된 모든 inventory 를 지웁니다. test isolation 을 위해 `@BeforeEach` 에서 사용합니다.
 * - 등록되지 않은 id 에 접근하면 [IllegalArgumentException] 이 발생합니다.
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

    /** 등록된 모든 inventory 를 지웁니다. `@BeforeEach` test isolation 에 사용합니다. */
    fun resetAll() {
        store.clear()
    }
}
