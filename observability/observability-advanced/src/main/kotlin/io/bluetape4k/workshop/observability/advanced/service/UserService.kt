package io.bluetape4k.workshop.observability.advanced.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.observability.advanced.model.User
import io.bluetape4k.workshop.observability.advanced.observation.observed
import io.bluetape4k.workshop.observability.advanced.repository.UserCacheRepository
import io.bluetape4k.workshop.observability.advanced.repository.UserRepository
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service

/**
 * Orchestrates user retrieval using a Redis cache-aside pattern with DB fallback.
 *
 * ## Behavior / Contract
 * - All public operations are wrapped in manual Observation spans.
 * - Redis failures are soft-failed: `CancellationException` is rethrown; other exceptions
 *   are logged as warnings and treated as cache misses.
 * - Does not use `runCatching {}` — explicit try/catch preserves structured concurrency.
 * - Requires `micrometer-context-propagation` for span continuity across dispatcher boundaries.
 */
@Service
class UserService(
    private val repo: UserRepository,
    private val cache: UserCacheRepository,
    private val observationRegistry: ObservationRegistry,
) {
    companion object : KLogging()

    /**
     * Retrieves a [User] by [id] using cache-aside pattern.
     *
     * Cache hit: returns cached value, skips DB span.
     * Cache miss: fetches from DB, stores in cache.
     * Redis error: logs warning, falls back to DB.
     */
    suspend fun getById(id: Long): User? =
        observed("user.service.get", observationRegistry) {
            // Cache read with soft-fail
            val cached = try {
                cache.get(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Redis cache read failed for id=$id, falling back to DB" }
                null
            }

            if (cached != null) {
                log.debug { "Cache hit for user id=$id" }
                return@observed cached
            }

            // DB fetch
            val user = observed("user.db.find", observationRegistry) {
                repo.findById(id)
            }

            // Cache write with soft-fail
            if (user != null) {
                try {
                    cache.put(user)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Redis cache write failed for id=$id" }
                }
            }

            user
        }

    /**
     * Creates and persists a new [User], returning the saved entity.
     */
    suspend fun create(user: User): User {
        observed("user.service.create", observationRegistry) {
            observed("user.db.save", observationRegistry) {
                repo.save(user)
            }
        }
        return user
    }
}
