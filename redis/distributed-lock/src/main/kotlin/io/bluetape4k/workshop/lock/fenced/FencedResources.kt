package io.bluetape4k.workshop.lock.fenced

import io.bluetape4k.logging.KLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of [FencedResource] instances keyed by resource id.
 *
 * ## Behavior / Contract
 * - [forResource] is idempotent — the same [FencedResource] is always returned for a given id.
 * - [resetAll] removes all entries; used in `@BeforeEach` for test isolation.
 * - This registry is **in-memory only** (workshop limitation):
 *   the fencing token history is lost on JVM restart, and the map is unbounded.
 */
class FencedResources {

    companion object : KLogging()

    private val map = ConcurrentHashMap<Long, FencedResource>()

    fun forResource(id: Long): FencedResource = map.computeIfAbsent(id) { FencedResource(it) }

    fun reset(id: Long) {
        map.remove(id)
    }

    /** Clears all fencing token state. Used for `@BeforeEach` test isolation. */
    fun resetAll() {
        map.clear()
    }
}
