package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration

/** deterministic fixture에 jitter를 추가하지 않고 capped exponential retry delay를 계산합니다. */
internal class NotificationRetryPolicy(
    val maxAttempts: Int = 5,
    private val initialDelay: Duration = 1.seconds(),
    private val maxDelay: Duration = 1.minutes(),
) {
    private companion object: KLogging() {
        const val MAX_SHIFT = 30
    }

    init {
        maxAttempts.requirePositiveNumber("maxAttempts")
        initialDelay.requirePositiveDuration("initialDelay")
        maxDelay.requirePositiveDuration("maxDelay")
        require(maxDelay >= initialDelay) { "maxDelay must not be shorter than initialDelay" }
    }

    fun delayAfter(failedAttempt: Int): Duration {
        failedAttempt.requirePositiveNumber("failedAttempt")
        val multiplier = 1L shl (failedAttempt - 1).coerceAtMost(MAX_SHIFT)
        val candidate = initialDelay.multipliedBy(multiplier)

        return minOf(candidate, maxDelay)
            .also { delay ->
                log.debug { "notification_retry_delay_calculated attempt=$failedAttempt delayMillis=${delay.toMillis()}" }
            }
    }
}
