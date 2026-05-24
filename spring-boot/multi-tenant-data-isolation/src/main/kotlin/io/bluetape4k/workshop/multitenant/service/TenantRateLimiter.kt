package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.workshop.multitenant.domain.TenantId
import org.springframework.stereotype.Component
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-window tenant-keyed rate limiter for workshop isolation tests.
 */
@Component
class TenantRateLimiter(
    private val keyFactory: TenantKeyFactory,
) {

    private val buckets = ConcurrentHashMap<String, RateLimitBucket>()

    /**
     * Consumes one request from the tenant/principal bucket.
     */
    fun tryConsume(
        tenantId: TenantId,
        principal: String,
        limit: Int = DEFAULT_LIMIT,
        window: Duration = DEFAULT_WINDOW,
    ): RateLimitDecision {
        require(limit > 0) { "limit must be positive" }
        require(!window.isZero && !window.isNegative) { "window must be positive" }

        val key = keyFactory.rateLimitKey(tenantId, principal)
        val windowMillis = window.toMillis()
        val windowStart = (System.currentTimeMillis() / windowMillis) * windowMillis
        val bucket = buckets.compute(key) { _, current ->
            if (current?.windowStartEpochMillis == windowStart) {
                current.copy(used = current.used + 1)
            } else {
                RateLimitBucket(windowStartEpochMillis = windowStart, used = 1)
            }
        } ?: RateLimitBucket(windowStartEpochMillis = windowStart, used = 1)

        return RateLimitDecision(
            key = key,
            allowed = bucket.used <= limit,
            remaining = (limit - bucket.used).coerceAtLeast(0),
            resetAtEpochMillis = bucket.windowStartEpochMillis + windowMillis,
        )
    }

    /**
     * Clears all limiter buckets.
     */
    fun clear() {
        buckets.clear()
    }

    companion object {
        private const val DEFAULT_LIMIT: Int = 2
        private val DEFAULT_WINDOW: Duration = Duration.ofMinutes(1)
    }
}

/**
 * Result of a tenant-keyed rate-limit check.
 */
data class RateLimitDecision(
    val key: String,
    val allowed: Boolean,
    val remaining: Int,
    val resetAtEpochMillis: Long,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 104L
    }
}

private data class RateLimitBucket(
    val windowStartEpochMillis: Long,
    val used: Int,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 104L
    }
}
