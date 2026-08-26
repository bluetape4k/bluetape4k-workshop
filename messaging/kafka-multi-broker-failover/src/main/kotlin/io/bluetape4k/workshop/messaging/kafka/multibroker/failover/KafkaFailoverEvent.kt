package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.io.Serializable

/**
 * 다중 broker 장애 조치 시 producer와 consumer가 공유하는 최소 reference event입니다.
 *
 * [eventId]는 application-level deduplication identity이고 [sequence]는 한 batch 안의
 * 결정적인 순서입니다. topic과 partition key는 reference 시나리오의 topology를
 * 보존하도록 고정합니다.
 */
data class KafkaFailoverEvent(
    val eventId: String,
    val sequence: Long,
    val payload: String,
    val partitionKey: String = PARTITION_KEY,
) : Serializable {

    init {
        eventId.requireNotBlank("eventId")
        sequence.requireZeroOrPositiveNumber("sequence")
        payload.requireNotBlank("payload")
        partitionKey.requireNotBlank("partitionKey")
        require(partitionKey == PARTITION_KEY) {
            "partitionKey must be $PARTITION_KEY"
        }
    }

    companion object {
        const val TOPIC: String = "kafka-failover-reference"
        const val PARTITION_KEY: String = "failover-partition-0"

        private const val serialVersionUID: Long = 1L
    }
}
