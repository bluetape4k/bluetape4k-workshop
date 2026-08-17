package io.bluetape4k.workshop.aws.kinesis

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** Kinesis 워크숍의 제한된 metric 이름과 tag만 기록하는 facade입니다. */
@Component
class KinesisWorkshopMetrics(
    private val meterRegistry: MeterRegistry,
) {

    fun incrementPublish(backend: String, outcome: String) =
        increment(PUBLISH, backend, OPERATION_PUBLISH, outcome)

    fun incrementConsume(backend: String, outcome: String) =
        increment(CONSUME, backend, OPERATION_CONSUME, outcome)

    fun incrementRetry(backend: String, operation: String) =
        increment(RETRY, backend, operation, OUTCOME_RETRY)

    fun incrementFailure(backend: String, operation: String) =
        increment(FAILURE, backend, operation, OUTCOME_FAILURE)

    fun increment(name: String, backend: String, operation: String, outcome: String) {
        require(name in ALLOWED_NAMES) { "metric name is not allowed." }
        meterRegistry.counter(
            name,
            TAG_BACKEND,
            backend,
            TAG_OPERATION,
            operation,
            TAG_OUTCOME,
            outcome,
        ).increment()
    }

    companion object {
        const val PUBLISH = "kinesis.workshop.publish"
        const val CONSUME = "kinesis.workshop.consume"
        const val RETRY = "kinesis.workshop.retry"
        const val FAILURE = "kinesis.workshop.failure"
        const val TAG_BACKEND = "backend"
        const val TAG_OPERATION = "operation"
        const val TAG_OUTCOME = "outcome"
        const val OPERATION_PUBLISH = "publish"
        const val OPERATION_CONSUME = "consume"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_RETRY = "retry"
        val ALLOWED_NAMES: Set<String> = setOf(PUBLISH, CONSUME, RETRY, FAILURE)
    }
}
