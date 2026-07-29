package io.bluetape4k.workshop.exposed.javers.persistence

import io.bluetape4k.javers.persistence.redis.repository.RedissonCdoSnapshotRepository
import io.bluetape4k.support.requireNotBlank
import org.javers.core.JaversBuilder
import org.redisson.api.RedissonClient

/**
 * 워크숍 모듈에서 사용할 Redis-backed order audit service를 만든다.
 *
 * ## 동작 / 계약
 * - [repositoryName]은 테스트와 예제가 서로 독립적으로 실행되도록 Redis key 범위를 나눈다.
 * - 반환된 service는 durable JaVers snapshot을 위해 [RedissonCdoSnapshotRepository]를 사용한다.
 * - 현재 order row는 Exposed에 남고, Redis는 audit history만 소유한다.
 *
 * ```kotlin
 * val service = RedisOrderAuditFactory.create("orders", redisson)
 * ```
 */
object RedisOrderAuditFactory {

    /**
     * Redisson JaVers repository를 사용하는 [OrderAuditService]를 만든다.
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
