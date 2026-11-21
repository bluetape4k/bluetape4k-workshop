package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import org.apache.kafka.common.errors.DisconnectException
import org.apache.kafka.common.errors.GroupIdNotFoundException
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.TimeoutException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** consumer group 관찰에서 재시도할 수 있는 Kafka 예외를 제한적으로 판별합니다. */
internal object KafkaFailoverGroupObservation {

    fun isTransient(error: Throwable): Boolean = when {
        error is GroupIdNotFoundException ||
                error is RetriableException ||
                error is DisconnectException ||
                error is TimeoutException ||
                error is java.util.concurrent.TimeoutException -> true

        error is ExecutionException || error is CompletionException ->
            error.cause?.let(::isTransient) == true

        else -> false
    }
}
