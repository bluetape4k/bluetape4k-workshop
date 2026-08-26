package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaFailoverCodec
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaFailoverEvent
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaFailoverKafkaConfiguration
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.nanoseconds

class KafkaFailoverClientFactoryTest {

    @Test
    fun `factory creates explicit string clients and partition zero records`() {
        val configuration = configuration()
        val factory = KafkaFailoverClientFactory(configuration)
        val event = KafkaFailoverEvent("event-1", 0L, "payload-1")

        val record = factory.newProducerRecord(event)
        record.topic() shouldBeEqualTo KafkaFailoverEvent.TOPIC
        record.partition() shouldBeEqualTo 0
        record.key() shouldBeEqualTo KafkaFailoverEvent.PARTITION_KEY
        record.value() shouldBeEqualTo KafkaFailoverCodec().encode(event)
        factory.producerProperties() shouldBeEqualTo configuration.producerProperties()
        factory.consumerProperties() shouldBeEqualTo configuration.consumerProperties()
        factory.adminProperties() shouldBeEqualTo configuration.adminProperties()
    }

    @Test
    fun `record metadata must confirm target partition`() {
        val factory = KafkaFailoverClientFactory(configuration())
        assertFailsWith<IllegalStateException> {
            factory.requirePartition(
                RecordMetadata(TopicPartition(KafkaFailoverEvent.TOPIC, 1), 0L, 0, 0L, 0, 0),
            )
        }
        factory.requirePartition(
            RecordMetadata(TopicPartition(KafkaFailoverEvent.TOPIC, 0), 0L, 0, 0L, 0, 0),
        )
    }

    @Test
    fun `batch sends await all futures under one shared deadline`() {
        val awaited = mutableListOf<Long>()
        val factory = KafkaFailoverClientFactory(configuration())
        val deadline = KafkaFailoverDeadline.fromNow(5_000_000_000L.nanoseconds)
        val futures = (0 until 4).map {
            java.util.concurrent.CompletableFuture.completedFuture(
                RecordMetadata(TopicPartition(KafkaFailoverEvent.TOPIC, 0), it.toLong(), 0, 0L, 0, 0),
            )
        }

        val result = factory.awaitBatch(
            futures = futures,
            deadline = deadline,
            remainingObserver = { awaited += it },
        )

        result shouldBeEqualTo 4
        awaited.size shouldBeEqualTo 4
        awaited.zipWithNext().all { (before, after) -> after <= before }.shouldBeTrue()
        awaited.all { it > 0L }.shouldBeTrue()
    }

    @Test
    fun `assignment barrier records callback generation and quiescence`() {
        val barrier = KafkaFailoverAssignmentBarrier()
        barrier.recordAssignment(
            partitions = setOf(TopicPartition(KafkaFailoverEvent.TOPIC, 0)),
            generation = 7,
        )
        val snapshot = barrier.await(KafkaFailoverDeadline.fromNow(1_000_000_000L.nanoseconds))
        snapshot.callbackCount shouldBeEqualTo 1
        snapshot.generation shouldBeEqualTo 7
        snapshot.partitions.size shouldBeEqualTo 1

        barrier.stop()
        barrier.callbackAfterStop.shouldBeFalse()
        barrier.recordAssignment(
            partitions = setOf(TopicPartition(KafkaFailoverEvent.TOPIC, 0)),
            generation = 8,
        )
        barrier.callbackAfterStop.shouldBeTrue()
    }

    @Test
    fun `collector applies first delivery, ignores duplicate and rejects conflict`() {
        val collector = KafkaFailoverCollector(
            codec = KafkaFailoverCodec(),
            maxBufferedRecords = 4,
            maxBufferedBytes = 1_024,
        )
        val first = KafkaFailoverEvent("same-id", 0L, "payload-1")
        val duplicate = KafkaFailoverEvent("same-id", 0L, "payload-1")
        val conflict = KafkaFailoverEvent("same-id", 0L, "payload-2")

        collector.accept(first, KafkaFailoverAcknowledgment { })
        collector.accept(duplicate, KafkaFailoverAcknowledgment { })
        assertFailsWith<KafkaFailoverConflictException> {
            collector.accept(conflict, KafkaFailoverAcknowledgment { })
        }

        val stats = collector.stats()
        stats.rawDeliveryCount shouldBeEqualTo 3
        stats.appliedCount shouldBeEqualTo 1
        stats.conflictCount shouldBeEqualTo 1
        stats.appliedEventIds shouldBeEqualTo setOf("same-id")
    }

    @Test
    fun `resource scope closes collector admin producer consumer in order and preserves suppressed failures`() {
        val scope = KafkaFailoverResourceScope()
        val closed = mutableListOf<String>()
        val first = IllegalStateException("collector-close")
        scope.registerCollector("collector") { closed += "collector"; throw first }
        scope.registerAdmin("admin") { closed += "admin"; throw IllegalArgumentException("admin-close") }
        scope.registerProducer("producer") { closed += "producer" }
        scope.registerConsumer("consumer") { closed += "consumer" }

        val error = assertFailsWith<IllegalStateException> { scope.close() }
        closed shouldBeEqualTo listOf("collector", "admin", "producer", "consumer")
        error.suppressed.size shouldBeEqualTo 1
        scope.close()
    }

    private fun configuration() = KafkaFailoverKafkaConfiguration("127.0.0.1:19092")
}
