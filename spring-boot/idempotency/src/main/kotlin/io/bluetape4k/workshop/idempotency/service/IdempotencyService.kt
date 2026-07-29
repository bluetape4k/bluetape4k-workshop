package io.bluetape4k.workshop.idempotency.service

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.idempotency.model.CachedResponse
import io.bluetape4k.workshop.idempotency.model.OrderRequest
import io.bluetape4k.workshop.idempotency.model.OrderResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Service
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Redisson [RMapCache] 기반 idempotent order creation 을 제공하는 service 입니다.
 *
 * ## 동작 / 계약
 * - 특정 key 의 첫 호출은 주문을 만들고 response 를 Redis 에 5분 TTL 로 저장한 뒤 HTTP 201 을 반환합니다.
 * - TTL 안에서 같은 key 로 들어온 후속 호출은 cached response 를 HTTP 200 으로 반환합니다.
 * - 같은 key 의 동시 최초 호출에서는 [RMapCache.putIfAbsent] 로 첫 writer 가 이깁니다.
 *   나중 writer 는 winner 가 만든 cached response 를 받습니다.
 * - 빈 idempotency key 는 이 service 호출 전 controller 책임으로 거부됩니다.
 */
@Service
class IdempotencyService(private val redisson: RedissonClient) {

    companion object : KLogging() {
        private const val CACHE_NAME = "idempotency:orders"
        private const val TTL_MINUTES = 5L
    }

    /**
     * 주문을 idempotent 하게 처리하려고 시도합니다.
     *
     * @param idempotencyKey 이 요청에 대해 client 가 제공하는 unique key 입니다.
     * @param request 주문 payload 입니다.
     * @return 새 response 인지 cached replay 인지를 나타내는 [IdempotencyResult] 입니다.
     */
    suspend fun processOrder(idempotencyKey: String, request: OrderRequest): IdempotencyResult =
        withContext(Dispatchers.IO) {
            val cache = redisson.getMapCache<String, CachedResponse>(CACHE_NAME)

            // 기존 cached response 를 먼저 확인합니다(fast path).
            val existing = cache.get(idempotencyKey)
            if (existing != null) {
                log.debug { "Cache HIT for idempotency key=$idempotencyKey" }
                return@withContext IdempotencyResult.Replay(existing)
            }

            // 새 order response 를 생성합니다.
            val newResponse = OrderResponse(
                orderId = Uuid.V7.nextIdAsString(),
                status = "CREATED",
                processedAt = Instant.now(),
            )
            val newCached = CachedResponse(httpStatus = 201, response = newResponse)

            // putIfAbsent: Redisson 을 통한 atomic SET NX semantics 입니다.
            val previous = cache.putIfAbsent(idempotencyKey, newCached, TTL_MINUTES, TimeUnit.MINUTES)

            if (previous == null) {
                // 현재 요청이 첫 writer 입니다.
                log.info { "Order created for idempotency key=$idempotencyKey, orderId=${newResponse.orderId}" }
                IdempotencyResult.Created(newCached)
            } else {
                // 동시 요청이 이미 값을 저장했으므로 cached result 를 제공합니다.
                log.debug { "Concurrent write detected for idempotency key=$idempotencyKey — returning cached response" }
                IdempotencyResult.Replay(previous)
            }
        }
}

/**
 * idempotent order processing 을 위한 sealed result type 입니다.
 */
sealed class IdempotencyResult : Serializable {
    /** 주문이 처음 생성되었습니다. HTTP 201 입니다. */
    data class Created(val cached: CachedResponse) : IdempotencyResult() {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /** 같은 idempotency key 를 이전에 본 적이 있습니다. 원래 payload 와 함께 HTTP 200 입니다. */
    data class Replay(val cached: CachedResponse) : IdempotencyResult() {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
