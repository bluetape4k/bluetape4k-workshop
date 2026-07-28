package io.bluetape4k.workshop.observability.advanced.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.observability.advanced.model.User
import io.bluetape4k.workshop.observability.advanced.observation.observed
import io.bluetape4k.workshop.observability.advanced.repository.UserCacheRepository
import io.bluetape4k.workshop.observability.advanced.repository.UserRepository
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service

/**
 * DB fallback 을 가진 Redis cache-aside pattern 으로 user retrieval 을 조율합니다.
 *
 * ## Behavior / Contract
 * - 모든 public operation 은 manual Observation span 으로 감쌉니다.
 * - Redis failure 는 soft-fail 로 처리합니다. `CancellationException` 은 다시 throw 하고, 다른 exception 은 warning 으로 log 한 뒤 cache miss 로 취급합니다.
 * - structured concurrency 를 보존하기 위해 `runCatching {}` 대신 명시적 try/catch 를 사용합니다.
 * - dispatcher boundary 를 넘어 span continuity 를 유지하려면 `micrometer-context-propagation` 이 필요합니다.
 */
@Service
class UserService(
    private val repo: UserRepository,
    private val cache: UserCacheRepository,
    private val observationRegistry: ObservationRegistry,
) {
    companion object : KLogging()

    /**
     * cache-aside pattern 으로 [id] 에 해당하는 [User] 를 조회합니다.
     *
     * cache hit: cached value 를 반환하고 DB span 은 건너뜁니다.
     * cache miss: DB 에서 조회한 뒤 cache 에 저장합니다.
     * Redis error: warning 을 log 하고 DB 로 fallback 합니다.
     */
    suspend fun getById(id: Long): User? =
        observed("user.service.get", observationRegistry) {
            val validId = id.requirePositiveNumber("id")
            // soft-fail 을 적용해 cache 를 읽습니다.
            val cached = try {
                cache.get(validId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Redis cache read failed for id=$validId, falling back to DB" }
                null
            }

            if (cached != null) {
                log.debug { "Cache hit for user id=$validId" }
                return@observed cached
            }

            // DB 에서 조회합니다.
            val user = observed("user.db.find", observationRegistry) {
                repo.findById(validId)
            }

            // soft-fail 을 적용해 cache 에 씁니다.
            if (user != null) {
                try {
                    cache.put(user)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Redis cache write failed for id=$validId" }
                }
            }

            user
        }

    /**
     * 새 [User] 를 생성하고 persist 한 뒤 saved entity 를 반환합니다.
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
