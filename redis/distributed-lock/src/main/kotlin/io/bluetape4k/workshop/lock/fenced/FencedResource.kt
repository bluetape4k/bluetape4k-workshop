package io.bluetape4k.workshop.lock.fenced

import io.bluetape4k.logging.KLogging
import kotlinx.atomicfu.atomic

/**
 * CAS-based fencing token guard for a single resource.
 *
 * ## Behavior / Contract
 * - Tracks the highest fencing token ever seen via a CAS loop.
 * - `apply(token, work)` returns `null` when `token < lastSeenToken` (stale holder).
 * - Equal tokens (`token == lastSeenToken`) are **allowed** — modelling re-entry within
 *   the same lease period (assumes [work] is idempotent).
 * - This guard is **in-memory only**: state resets on JVM restart (workshop limitation).
 *
 * ## Usage
 * ```kotlin
 * val resource = FencedResource(inventoryId)
 * val result = resource.apply(token) {
 *     store.applyChange(id, -qty)
 * }
 * if (result == null) return Rejected(token)
 * ```
 */
class FencedResource(val resourceId: Long) {

    companion object : KLogging()

    private val lastSeenToken = atomic(0L)

    /**
     * Applies [work] only if [token] is not stale (not strictly less than the last seen token).
     *
     * @return the result of [work], or `null` if [token] is stale.
     */
    fun <T : Any> apply(token: Long, work: () -> T): T? {
        while (true) {
            val current = lastSeenToken.value
            if (token < current) return null  // stale: strict less-than check
            if (lastSeenToken.compareAndSet(current, maxOf(current, token))) {
                return work()
            }
            // CAS lost to a concurrent update; retry
        }
    }
}
