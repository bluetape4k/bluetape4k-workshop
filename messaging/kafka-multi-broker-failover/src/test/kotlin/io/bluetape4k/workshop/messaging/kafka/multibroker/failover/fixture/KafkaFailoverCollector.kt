package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaFailoverCodec
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaFailoverEvent
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

fun interface KafkaFailoverAcknowledgment {
    fun acknowledge()
}

class KafkaFailoverConflictException(message: String) : IllegalStateException(message)

class KafkaFailoverBufferLimitException(message: String) : IllegalStateException(message)

data class KafkaFailoverCollectorStats(
    val rawDeliveryCount: Int,
    val appliedCount: Int,
    val conflictCount: Int,
    val retryCount: Int,
    val appliedEventIds: Set<String>,
    val maxBufferedRecords: Int,
    val maxBufferedBytes: Long,
)

/**
 * application-level identity/fingerprint deduplication을 적용하는 at-least-once collector입니다.
 * callback state를 소유하지만 underlying consumer의 소유권은 resource scope에 있습니다.
 */
class KafkaFailoverCollector(
    private val codec: KafkaFailoverCodec = KafkaFailoverCodec(),
    private val maxBufferedRecords: Int = 100,
    private val maxBufferedBytes: Long = 1_048_576L,
) {
    private val lock = Any()
    private val fingerprints = linkedMapOf<String, String>()
    private val appliedIds = linkedSetOf<String>()
    private var rawCount = 0
    private var applied = 0
    private var conflicts = 0
    private var retryCountValue = 0
    private var bufferedRecords = 0
    private var bufferedBytes = 0L
    private var maxBufferedRecordsValue = 0
    private var maxBufferedBytesValue = 0L
    private val running = AtomicBoolean(false)
    private val callbackCountValue = AtomicInteger(0)
    private val callbackAfterStopValue = AtomicBoolean(false)
    private val rebalanceRequested = AtomicBoolean(false)
    @Volatile
    private var consumerRef: Consumer<String, String>? = null
    private var worker: Thread? = null

    init {
        require(maxBufferedRecords > 0) { "maxBufferedRecords must be positive" }
        require(maxBufferedBytes > 0) { "maxBufferedBytes must be positive" }
    }

    val callbackCount: Int
        get() = callbackCountValue.get()

    val callbackAfterStop: Boolean
        get() = callbackAfterStopValue.get()

    /** collector thread가 수행한 제한된 consumer poll 횟수입니다. */
    val pollCount: Int
        get() = pollCountValue.get()

    private val pollCountValue = AtomicInteger(0)

    fun accept(event: KafkaFailoverEvent, acknowledgment: KafkaFailoverAcknowledgment) {
        acceptEncoded(codec.encode(event), acknowledgment)
    }

    fun accept(record: ConsumerRecord<String, String>, acknowledgment: KafkaFailoverAcknowledgment) {
        acceptEncoded(record.value(), acknowledgment)
    }

    fun acceptEncoded(encoded: String, acknowledgment: KafkaFailoverAcknowledgment) {
        val event = codec.decode(encoded)
        val fingerprint = codec.fingerprint(event)
        synchronized(lock) {
            rawCount += 1
            bufferedRecords += 1
            bufferedBytes += encoded.toByteArray(Charsets.UTF_8).size
            maxBufferedRecordsValue = maxOf(maxBufferedRecordsValue, bufferedRecords)
            maxBufferedBytesValue = maxOf(maxBufferedBytesValue, bufferedBytes)
            if (bufferedRecords > maxBufferedRecords || bufferedBytes > maxBufferedBytes) {
                bufferedRecords -= 1
                bufferedBytes -= encoded.toByteArray(Charsets.UTF_8).size
                throw KafkaFailoverBufferLimitException("collector buffer ceiling exceeded")
            }

            val previous = fingerprints[event.eventId]
            if (previous != null && previous != fingerprint) {
                conflicts += 1
                bufferedRecords -= 1
                bufferedBytes -= encoded.toByteArray(Charsets.UTF_8).size
                throw KafkaFailoverConflictException("event identity fingerprint conflict")
            }
            if (previous == null) fingerprints[event.eventId] = fingerprint
        }

        try {
            acknowledgment.acknowledge()
            synchronized(lock) {
                if (event.eventId !in appliedIds) {
                    appliedIds += event.eventId
                    applied += 1
                }
            }
        } finally {
            synchronized(lock) {
                bufferedRecords = (bufferedRecords - 1).coerceAtLeast(0)
                bufferedBytes = (bufferedBytes - encoded.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0L)
            }
        }
    }

    fun awaitApplied(expectedCount: Int, deadline: KafkaFailoverDeadline): KafkaFailoverCollectorStats {
        require(expectedCount >= 0) { "expectedCount must not be negative" }
        while (deadline.remainingNanos() > 0L) {
            val stats = stats()
            if (stats.appliedCount >= expectedCount) return stats
            Thread.sleep(minOf(250L, (deadline.remainingNanos() / 1_000_000L).coerceAtLeast(1L)))
        }
        throw java.util.concurrent.TimeoutException("collector applied count deadline exhausted")
    }

    /** coordinator fault 이후 consumer thread가 소유한 rebalance를 요청합니다. */
    fun requestRebalance() {
        check(running.get()) { "collector is not running" }
        rebalanceRequested.set(true)
    }

    fun start(
        consumer: Consumer<String, String>,
        topic: String = KafkaFailoverEvent.TOPIC,
        assignmentBarrier: KafkaFailoverAssignmentBarrier? = null,
    ) {
        check(running.compareAndSet(false, true)) { "collector is already running" }
        consumerRef = consumer
        consumer.subscribe(
            listOf(topic),
            object : org.apache.kafka.clients.consumer.ConsumerRebalanceListener {
                override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) = Unit

                override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
                    if (!running.get()) {
                        callbackAfterStopValue.set(true)
                        return
                    }
                    callbackCountValue.incrementAndGet()
                    assignmentBarrier?.recordAssignment(partitions.toSet(), null)
                }
            },
        )
        worker = thread(isDaemon = true, name = "kafka-failover-collector") {
            try {
                while (running.get()) {
                    if (rebalanceRequested.compareAndSet(true, false)) {
                        consumer.enforceRebalance()
                    }
                    pollCountValue.incrementAndGet()
                    val records = consumer.poll(Duration.ofMillis(250))
                    records.forEach { record ->
                        // collector의 명시적 acknowledgment가 application 경계입니다.
                        // retry를 관찰할 수 있도록 commit은 scenario harness가 소유합니다.
                        accept(record, KafkaFailoverAcknowledgment { })
                    }
                }
            } catch (_: WakeupException) {
                // stop()은 owner를 닫지 않고 wakeup으로 blocking poll을 종료합니다.
            } finally {
                running.set(false)
            }
        }
    }

    fun stop(deadline: KafkaFailoverDeadline): Boolean {
        running.set(false)
        consumerRef?.wakeup()
        worker?.interrupt()
        val current = worker ?: return true
        current.join((deadline.remainingNanos() / 1_000_000L).coerceAtLeast(1L))
        return !current.isAlive
    }

    fun stats(): KafkaFailoverCollectorStats = synchronized(lock) {
        KafkaFailoverCollectorStats(
            rawDeliveryCount = rawCount,
            appliedCount = applied,
            conflictCount = conflicts,
            retryCount = retryCountValue,
            appliedEventIds = appliedIds.toSet(),
            maxBufferedRecords = maxBufferedRecordsValue,
            maxBufferedBytes = maxBufferedBytesValue,
        )
    }
}
