package io.bluetape4k.workshop.observability.advanced.repository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.micrometer.observation.coroutines.withObservationContextSuspending
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.observability.advanced.model.User
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RMapCache
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Repository
import java.util.concurrent.TimeUnit

/**
 * Redisson `RMapCache` 를 사용하는 [User] object 용 Redis cache repository 입니다.
 *
 * ## Behavior / Contract
 * - cache read/write 는 released `withObservationContextSuspending` helper 를 통한 Observation span 으로 instrumentation 됩니다.
 * - Observation context 를 유지하면서 내부에서 `withContext(Dispatchers.IO)` 를 호출합니다.
 * - cached entry 의 TTL 기본값은 60초입니다.
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
     * [id] 로 cache 에서 [User] 를 조회합니다.
     *
     * cache miss 이면 `null` 을 반환합니다.
     */
    suspend fun get(id: Long): User? =
        withObservationContextSuspending("user.cache.get", observationRegistry) {
            val validId = id.requirePositiveNumber("id")
            withContext(Dispatchers.IO) {
                cache[validId]
            }
        }

    /**
     * 주어진 [ttlSeconds] TTL 로 [User] 를 cache 에 저장합니다.
     */
    suspend fun put(user: User, ttlSeconds: Long = 60L) {
        withObservationContextSuspending<Unit>("user.cache.put", observationRegistry) {
            val ttl = ttlSeconds.requirePositiveNumber("ttlSeconds")
            withContext(Dispatchers.IO) {
                // RMapCache.put() 은 previous value(V?) 를 반환하므로 버립니다.
                cache.put(user.id, user, ttl, TimeUnit.SECONDS)
                Unit
            }
        }
    }

    /**
     * [id] 로 cache 에서 [User] 를 제거합니다. test isolation 용도이므로 instrumentation 하지 않습니다.
     */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        cache.remove(id.requirePositiveNumber("id"))
    }
}
