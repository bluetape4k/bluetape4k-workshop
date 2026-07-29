package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration

/** deterministic fixture에 jitter를 추가하지 않고 capped exponential retry delay를 계산합니다. */
internal class NotificationRetryPolicy(
    val maxAttempts: Int = 5,
    private val initialDelay: Duration = Duration.ofSeconds(1),
    private val maxDelay: Duration = Duration.ofMinutes(1),
) {
    init {
        maxAttempts.requirePositiveNumber("maxAttempts")
        require(!initialDelay.isNegative && !initialDelay.isZero) { "initialDelay must be positive" }
        require(!maxDelay.isNegative && !maxDelay.isZero) { "maxDelay must be positive" }
        require(maxDelay >= initialDelay) { "maxDelay must not be shorter than initialDelay" }
    }

    fun delayAfter(failedAttempt: Int): Duration {
        failedAttempt.requirePositiveNumber("failedAttempt")
        val multiplier = 1L shl (failedAttempt - 1).coerceAtMost(MAX_SHIFT)
        val candidate = initialDelay.multipliedBy(multiplier)
        return minOf(candidate, maxDelay).also { delay ->
            log.debug { "notification_retry_delay_calculated attempt=$failedAttempt delayMillis=${delay.toMillis()}" }
        }
    }

    private companion object : KLogging() {
        const val MAX_SHIFT = 30
    }
}
