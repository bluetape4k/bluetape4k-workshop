package io.bluetape4k.workshop.exposed.javers.persistence

import io.bluetape4k.javers.persistence.redis.repository.RedissonCdoSnapshotRepository
import io.bluetape4k.support.requireNotBlank
import org.javers.core.JaversBuilder
import org.redisson.api.RedissonClient

/**
 * Creates Redis-backed order audit services for the workshop module.
 *
 * ## Behavior / Contract
 * - [repositoryName] scopes Redis keys so tests and examples can run independently.
 * - The returned service uses [RedissonCdoSnapshotRepository] for durable JaVers snapshots.
 * - The current order row remains in Exposed; Redis owns audit history only.
 *
 * ```kotlin
 * val service = RedisOrderAuditFactory.create("orders", redisson)
 * ```
 */
object RedisOrderAuditFactory {

    /**
     * Builds an [OrderAuditService] backed by a Redisson JaVers repository.
     */
    fun create(repositoryName: String, redisson: RedissonClient): OrderAuditService {
        repositoryName.requireNotBlank("repositoryName")
        val repository = RedissonCdoSnapshotRepository(repositoryName, redisson)
        val javers = JaversBuilder.javers()
            .registerJaversRepository(repository)
            .build()
        return OrderAuditService(javers)
    }
}
