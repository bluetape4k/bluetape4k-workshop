package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaFailoverCodec
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaFailoverEvent
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaFailoverKafkaConfiguration
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import java.util.Properties
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** reference scenario에서 사용하는 명시적 문자열 client를 생성합니다. */
class KafkaFailoverClientFactory(
    private val configuration: KafkaFailoverKafkaConfiguration,
    private val codec: KafkaFailoverCodec = KafkaFailoverCodec(),
) {

    fun producer(): KafkaProducer<String, String> = KafkaProducer(properties(configuration.producerProperties()))

    fun consumer(): KafkaConsumer<String, String> = KafkaConsumer(properties(configuration.consumerProperties()))

    fun admin(): Admin = Admin.create(properties(configuration.adminProperties()))

    fun producerProperties(): Map<String, Any> = configuration.producerProperties()

    fun consumerProperties(): Map<String, Any> = configuration.consumerProperties()

    fun adminProperties(): Map<String, Any> = configuration.adminProperties()

    fun newProducerRecord(
        event: KafkaFailoverEvent,
        partition: Int = KafkaFailoverTopology.PARTITION,
    ): ProducerRecord<String, String> {
        require(partition in 0 until KafkaFailoverTopology.PARTITION_COUNT) {
            "Kafka partition must be within the reference topic"
        }
        return ProducerRecord(
            configuration.topic,
            partition,
            event.partitionKey,
            codec.encode(event),
        )
    }

    fun requirePartition(
        metadata: RecordMetadata,
        expectedPartition: Int = KafkaFailoverTopology.PARTITION,
    ): RecordMetadata {
        check(metadata.topic() == configuration.topic) {
            "Kafka metadata topic does not match the reference topic"
        }
        check(metadata.partition() == expectedPartition) {
            "Kafka metadata partition must be $expectedPartition"
        }
        return metadata
    }

    /** 모든 producer future를 동일한 공유 phase deadline 안에서 기다립니다. */
    fun awaitBatch(
        futures: Collection<Future<RecordMetadata>>,
        deadline: KafkaFailoverDeadline,
        expectedPartition: Int = KafkaFailoverTopology.PARTITION,
        remainingObserver: (Long) -> Unit = {},
    ): Int {
        futures.forEach { future ->
            val remaining = deadline.remainingNanos()
            remainingObserver(remaining)
            deadline.awaitBlocking("producer-ack") {
                requirePartition(future.get(remaining, TimeUnit.NANOSECONDS), expectedPartition)
            }
        }
        return futures.size
    }

    private fun properties(source: Map<String, Any>): Properties = Properties().also { properties ->
        source.forEach { (key, value) -> properties[key] = value }
    }
}

data class KafkaFailoverAssignmentSnapshot(
    val callbackCount: Int,
    val generation: Int?,
    val partitions: Set<org.apache.kafka.common.TopicPartition>,
)

/** 제한된 assignment callback barrier이며 stop 이후 quiescence도 추적합니다. */
class KafkaFailoverAssignmentBarrier {
    private val latch = java.util.concurrent.CountDownLatch(1)
    private val lock = Any()
    private var stopped = false
    private var callbackAfterStopValue = false
    private var callbackCountValue = 0
    private var generationValue: Int? = null
    private var partitionsValue: Set<org.apache.kafka.common.TopicPartition> = emptySet()

    val callbackCount: Int
        get() = synchronized(lock) { callbackCountValue }

    val callbackAfterStop: Boolean
        get() = synchronized(lock) { callbackAfterStopValue }

    fun recordAssignment(partitions: Set<org.apache.kafka.common.TopicPartition>, generation: Int?) {
        synchronized(lock) {
            if (stopped) {
                callbackAfterStopValue = true
                return
            }
            callbackCountValue += 1
            generationValue = generation
            partitionsValue = partitions.toSet()
            latch.countDown()
        }
    }

    fun await(deadline: KafkaFailoverDeadline): KafkaFailoverAssignmentSnapshot {
        deadline.awaitBlocking("assignment") {
            if (!latch.await(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                throw java.util.concurrent.TimeoutException("assignment barrier deadline exhausted")
            }
        }
        return synchronized(lock) {
            KafkaFailoverAssignmentSnapshot(callbackCountValue, generationValue, partitionsValue)
        }
    }

    fun stop() {
        synchronized(lock) { stopped = true }
    }
}
