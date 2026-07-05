package io.bluetape4k.workshop.aws.sqssns

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import kotlin.coroutines.cancellation.CancellationException

/**
 * Micrometer metrics for the learner-visible SQS/SNS workflow outcomes.
 */
@Component
class OrderNotificationMetrics(
    private val meterRegistry: MeterRegistry,
) {

    suspend fun <T> recordPublish(block: suspend () -> T): T {
        val sample = Timer.start(meterRegistry)
        var result = RESULT_SUCCESS
        try {
            return block()
        } catch (e: CancellationException) {
            result = RESULT_CANCELLED
            throw e
        } catch (e: Exception) {
            result = RESULT_FAILURE
            throw e
        } finally {
            counter(PUBLISH_ATTEMPTS, result).increment()
            sample.stop(timer(PUBLISH_LATENCY, "publish", result))
        }
    }

    suspend fun <T> recordConsume(
        result: String,
        block: suspend () -> T,
    ): T {
        val sample = Timer.start(meterRegistry)
        var recordedResult = result
        try {
            return block()
        } catch (e: CancellationException) {
            recordedResult = RESULT_CANCELLED
            throw e
        } catch (e: Exception) {
            recordedResult = RESULT_FAILURE
            throw e
        } finally {
            counter(CONSUME_MESSAGES, recordedResult).increment()
            sample.stop(timer(CONSUME_LATENCY, "consume", recordedResult))
        }
    }

    private fun counter(name: String, result: String): Counter =
        Counter.builder(name)
            .tag(TAG_RESULT, result)
            .register(meterRegistry)

    private fun timer(name: String, operation: String, result: String): Timer =
        Timer.builder(name)
            .tag(TAG_OPERATION, operation)
            .tag(TAG_RESULT, result)
            .register(meterRegistry)

    companion object {
        const val PUBLISH_ATTEMPTS: String = "workshop.aws.sqs-sns.publish.attempts"
        const val PUBLISH_LATENCY: String = "workshop.aws.sqs-sns.publish.latency"
        const val CONSUME_MESSAGES: String = "workshop.aws.sqs-sns.consume.messages"
        const val CONSUME_LATENCY: String = "workshop.aws.sqs-sns.consume.latency"
        const val TAG_OPERATION: String = "operation"
        const val TAG_RESULT: String = "result"
        const val RESULT_SUCCESS: String = "success"
        const val RESULT_FAILURE: String = "failure"
        const val RESULT_CANCELLED: String = "cancelled"
        const val RESULT_ACKED: String = "acked"
        const val RESULT_RETRY: String = "retry"
        const val RESULT_DEAD_LETTER: String = "dead-letter"
    }
}
