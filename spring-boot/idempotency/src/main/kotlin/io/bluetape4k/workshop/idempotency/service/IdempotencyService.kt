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
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Service that provides idempotent order creation backed by Redisson [RMapCache].
 *
 * ## Behavior / Contract
 * - First call with a given key creates an order, stores the response in Redis with a 5-minute TTL,
 *   and returns HTTP 201.
 * - Subsequent calls with the same key within the TTL return the cached response with HTTP 200.
 * - Concurrent first-time calls with the same key: the first writer wins via [RMapCache.putIfAbsent];
 *   the loser receives a 409 Conflict immediately (no waiting).
 * - Blank idempotency keys are rejected before this service is called (controller responsibility).
 */
@Service
class IdempotencyService(private val redisson: RedissonClient) {

    companion object : KLogging() {
        private const val CACHE_NAME = "idempotency:orders"
        private const val TTL_MINUTES = 5L
    }

    /**
     * Attempts to process an order idempotently.
     *
     * @param idempotencyKey unique client-supplied key for this request
     * @param request the order payload
     * @return [IdempotencyResult] indicating whether this is a new response or a cached replay
     */
    suspend fun processOrder(idempotencyKey: String, request: OrderRequest): IdempotencyResult =
        withContext(Dispatchers.IO) {
            val cache = redisson.getMapCache<String, CachedResponse>(CACHE_NAME)

            // Check for existing cached response first (fast path)
            val existing = cache.get(idempotencyKey)
            if (existing != null) {
                log.debug { "Cache HIT for idempotency key=$idempotencyKey" }
                return@withContext IdempotencyResult.Replay(existing)
            }

            // Create a new order response
            val newResponse = OrderResponse(
                orderId = Uuid.V7.nextIdAsString(),
                status = "CREATED",
                processedAt = Instant.now(),
            )
            val newCached = CachedResponse(httpStatus = 201, response = newResponse)

            // putIfAbsent: atomic SET NX semantics via Redisson
            val previous = cache.putIfAbsent(idempotencyKey, newCached, TTL_MINUTES, TimeUnit.MINUTES)

            if (previous == null) {
                // We were the first writer
                log.info { "Order created for idempotency key=$idempotencyKey, orderId=${newResponse.orderId}" }
                IdempotencyResult.Created(newCached)
            } else {
                // A concurrent request already stored a value; serve that cached result
                log.debug { "Concurrent write detected for idempotency key=$idempotencyKey — returning cached response" }
                IdempotencyResult.Replay(previous)
            }
        }
}

/**
 * Sealed result type for idempotent order processing.
 */
sealed class IdempotencyResult {
    /** The order was created for the first time. HTTP 201. */
    data class Created(val cached: CachedResponse) : IdempotencyResult()

    /** The same idempotency key was seen before. HTTP 200 with original payload. */
    data class Replay(val cached: CachedResponse) : IdempotencyResult()
}
