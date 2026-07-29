package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.multitenant.domain.TenantId
import org.springframework.stereotype.Component
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * workshop isolation test 를 위한 fixed-window tenant-keyed rate limiter 입니다.
 */
@Component
class TenantRateLimiter(
    private val keyFactory: TenantKeyFactory,
) {

    private val buckets = ConcurrentHashMap<String, RateLimitBucket>()

    /**
     * tenant/principal bucket 에서 요청 하나를 소비합니다.
     */
    fun tryConsume(
        tenantId: TenantId,
        principal: String,
        limit: Int = DEFAULT_LIMIT,
        window: Duration = DEFAULT_WINDOW,
    ): RateLimitDecision {
        limit.requirePositiveNumber("limit")
        val windowMillis = window.toMillis()
        windowMillis.requirePositiveNumber("windowMillis")

        val key = keyFactory.rateLimitKey(tenantId, principal)
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
     * 모든 limiter bucket 을 지웁니다.
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
 * tenant-keyed rate-limit check 결과입니다.
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
