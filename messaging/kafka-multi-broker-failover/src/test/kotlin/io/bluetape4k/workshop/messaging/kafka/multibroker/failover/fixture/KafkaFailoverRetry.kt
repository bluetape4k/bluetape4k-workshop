package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import org.apache.kafka.common.errors.DisconnectException
import org.apache.kafka.common.errors.RetriableException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlin.math.min

/** Admin metadata/config/ISR 조회에만 사용하는 bounded retry 결과입니다. */
data class KafkaFailoverRetryResult<T>(
    val value: T,
    val retryCount: Int,
)

/**
 * Testcontainers lifecycle에는 적용하지 않고 AdminClient의 일시 오류만 재시도합니다.
 * producer 내부 retry는 이 결과의 retryCount에 포함하지 않습니다.
 */
class KafkaFailoverRetry(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var retryCountValue = 0

    /** 이 helper가 수행한 AdminClient retry 횟수입니다. */
    val retryCount: Int
        get() = retryCountValue

    fun <T> execute(
        deadline: KafkaFailoverDeadline,
        phase: String,
        operation: () -> T,
    ): KafkaFailoverRetryResult<T> {
        var retryCount = 0
        var backoffMillis = INITIAL_BACKOFF_MILLIS

        while (true) {
            try {
                return KafkaFailoverRetryResult(operation(), retryCount)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isRetryable(error) || retryCount >= MAX_RETRY_ATTEMPTS) {
                    throw error
                }
                retryCount += 1
                retryCountValue += 1
                val remainingMillis = deadline.remainingNanos() / NANOS_PER_MILLI
                if (remainingMillis <= 0L) {
                    throw TimeoutException("phase=$phase deadline exhausted after retry=$retryCount")
                }
                sleep(min(backoffMillis, remainingMillis))
                backoffMillis = min(backoffMillis * 2, MAX_BACKOFF_MILLIS)
            }
        }
    }

    private fun isRetryable(error: Throwable): Boolean =
        error is RetriableException ||
            error is DisconnectException ||
            error is TimeoutException ||
            error.cause?.let(::isRetryable) == true

    companion object {
        const val MAX_RETRY_ATTEMPTS: Int = 5
        private const val INITIAL_BACKOFF_MILLIS: Long = 200L
        private const val MAX_BACKOFF_MILLIS: Long = 2_000L
        private const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
