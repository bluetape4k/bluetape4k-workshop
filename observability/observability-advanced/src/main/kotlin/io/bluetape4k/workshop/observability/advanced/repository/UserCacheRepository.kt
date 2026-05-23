package io.bluetape4k.workshop.observability.advanced.repository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.observability.advanced.model.User
import io.bluetape4k.workshop.observability.advanced.observation.observed
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RMapCache
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Repository
import java.util.concurrent.TimeUnit

/**
 * Redis cache repository for [User] objects using Redisson `RMapCache`.
 *
 * ## Behavior / Contract
 * - Cache reads/writes are instrumented with manual Observation spans via [observed].
 * - Uses [observed] (Observation OUTER) with `withContext(Dispatchers.IO)` inside.
 * - TTL defaults to 60 seconds per cached entry.
 */
@Repository
class UserCacheRepository(
    private val redisson: RedissonClient,
    private val observationRegistry: ObservationRegistry,
) {
    companion object : KLogging()

    private val cache: RMapCache<Long, User> by lazy {
        redisson.getMapCache("workshop:observability:users")
    }

    /**
     * Retrieves a [User] from the cache by [id].
     *
     * Returns `null` on cache miss.
     */
    suspend fun get(id: Long): User? =
        observed("user.cache.get", observationRegistry) {
            withContext(Dispatchers.IO) {
                cache[id]
            }
        }

    /**
     * Stores a [User] in the cache with the given [ttlSeconds] TTL.
     */
    suspend fun put(user: User, ttlSeconds: Long = 60L) {
        observed("user.cache.put", observationRegistry) {
            withContext(Dispatchers.IO) {
                // RMapCache.put() returns the previous value (V?); discard it
                cache.put(user.id, user, ttlSeconds, TimeUnit.SECONDS)
                Unit
            }
        }
    }

    /**
     * Removes a [User] from the cache by [id]. Used for test isolation; not instrumented.
     */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        cache.remove(id)
    }
}
