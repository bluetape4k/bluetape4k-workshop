package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.kinesis.InMemoryKinesisCheckpointStore
import io.bluetape4k.aws.kinesis.InMemoryKinesisLeaseStore
import io.bluetape4k.aws.kinesis.KinesisCheckpoint
import io.bluetape4k.aws.kinesis.KinesisCheckpointStore
import io.bluetape4k.aws.kinesis.KinesisConsumerOptions
import io.bluetape4k.aws.kinesis.KinesisLease
import io.bluetape4k.aws.kinesis.KinesisLeaseLostException
import io.bluetape4k.aws.kinesis.KinesisLeaseStore
import io.bluetape4k.aws.kinesis.KinesisRecordFlowOptions
import io.bluetape4k.aws.kinesis.KinesisShardKey
import io.bluetape4k.aws.kinesis.KinesisShardRecord
import io.bluetape4k.aws.kinesis.KinesisStartingPosition
import io.bluetape4k.aws.kinesis.consumerFlow
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.junit5.coroutines.runSuspendIO
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes

class KinesisConsumerFlowTest {

    @Test
    fun `local consumer discovers shards and saves checkpoints only after downstream emit`() = runSuspendIO {
        val client = localClient()
        append(client, "1", PRIMARY_SHARD)
        append(client, "2", PRIMARY_SHARD)
        append(client, "3", SECONDARY_SHARD)
        append(client, "4", SECONDARY_SHARD)

        val emitted = ConcurrentHashMap.newKeySet<String>()
        val checkpointStore = RecordingCheckpointStore(setOf(PRIMARY_SHARD, SECONDARY_SHARD), emitted)
        val leaseStore = RecordingLeaseStore(InMemoryKinesisLeaseStore())
        val observed = mutableListOf<KinesisShardRecord>()
        val firstRecord = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val consumer = client.consumerFlow(
            streamName = STREAM_NAME,
            consumerGroup = CONSUMER_GROUP,
            streamIdentity = STREAM_IDENTITY,
            position = KinesisStartingPosition.TrimHorizon,
            options = consumerOptions(maxShardConcurrency = 1),
            checkpointStore = checkpointStore,
            leaseStore = leaseStore,
        )

        val collector: Job = launch {
            consumer.collect { record ->
                emitted += record.record.sequenceNumber()
                synchronized(observed) { observed += record }
                firstRecord.complete(Unit)
                if (record.record.sequenceNumber() == "1") releaseCollector.await()
            }
        }
        firstRecord.await()
        leaseStore.maxObservedActive shouldBeEqualTo 1
        releaseCollector.complete(Unit)
        checkpointStore.awaitShardEnds()
        collector.cancelAndJoin()

        client.discoveredShardIds.size shouldBeEqualTo 2
        synchronized(observed) {
            observed.groupBy { it.shardId }.mapValues { (_, records) ->
                records.map { it.record.sequenceNumber() }
            } shouldBeEqualTo mapOf(
                PRIMARY_SHARD to listOf("1", "2"),
                SECONDARY_SHARD to listOf("3", "4"),
            )
        }
    }

    @Test
    fun `service collector failure releases tracked lease for a replacement owner`() = runSuspendIO {
        val client = localClient()
        val leaseStore = RecordingLeaseStore(InMemoryKinesisLeaseStore())
        val key = KinesisShardKey(STREAM_IDENTITY, CONSUMER_GROUP, PRIMARY_SHARD)
        appendEvent(client)
        val service = service(client, leaseStore)

        assertFailsWith<IllegalStateException> {
            service.consume().collect {
                error("collector failure")
            }
        }

        check(leaseStore.releasedKeys.contains(key)) {
            "expected collector failure to release $key, events=${leaseStore.events}"
        }
        val replacement = leaseStore.acquire(
            key,
            ownerId = "replacement-owner",
            leaseDuration = 1.seconds,
        )
        replacement?.ownerId shouldBeEqualTo "replacement-owner"
    }

    @Test
    fun `service take cancellation releases tracked lease for a replacement owner`() = runSuspendIO {
        val client = localClient()
        appendEvent(client)
        val leaseStore = RecordingLeaseStore(InMemoryKinesisLeaseStore())
        val key = KinesisShardKey(STREAM_IDENTITY, CONSUMER_GROUP, PRIMARY_SHARD)

        service(client, leaseStore).consume().take(1).toList()

        check(leaseStore.releasedKeys.contains(key)) {
            "expected take cancellation to release $key, events=${leaseStore.events}"
        }
        val replacement = leaseStore.acquire(
            key,
            ownerId = "replacement-owner",
            leaseDuration = 1.seconds,
        )
        replacement?.ownerId shouldBeEqualTo "replacement-owner"
    }

    @Test
    fun `stale lease cannot release current owner or overwrite checkpoint`() = runSuspendIO {
        val clock = MutableClock(Instant.parse("2026-08-30T00:00:00Z"))
        val key = KinesisShardKey(STREAM_IDENTITY, CONSUMER_GROUP, PRIMARY_SHARD)
        val leaseStore = InMemoryKinesisLeaseStore(clock)
        val checkpointStore = InMemoryKinesisCheckpointStore()
        val stale = requireNotNull(leaseStore.acquire(key, "owner-a", 1.seconds))

        clock.advanceSeconds(2)
        val current = requireNotNull(leaseStore.acquire(key, "owner-b", 1.seconds))
        leaseStore.release(stale)
        leaseStore.acquire(key, "owner-c", 1.seconds) shouldBeEqualTo null

        checkpointStore.save(key, KinesisCheckpoint.Sequence("2"), current)
        assertFailsWith<KinesisLeaseLostException> {
            checkpointStore.save(key, KinesisCheckpoint.Sequence("3"), stale)
        }
        checkpointStore.load(key) shouldBeEqualTo KinesisCheckpoint.Sequence("2")
    }

    private fun localClient(): LocalKinesisConsumerClient = LocalKinesisConsumerClient(
        configuredStreamName = STREAM_NAME,
        configuredShardId = PRIMARY_SHARD,
        additionalShardIds = listOf(SECONDARY_SHARD),
    )

    private fun append(client: LocalKinesisConsumerClient, sequenceNumber: String, shardId: String) {
        client.append(
            sequenceNumber = sequenceNumber,
            partitionKey = "partition-$shardId",
            data = SdkBytes.fromUtf8String("payload-$sequenceNumber"),
            shardId = shardId,
        )
    }

    private fun appendEvent(client: LocalKinesisConsumerClient) {
        client.append(
            sequenceNumber = "1",
            partitionKey = "orders",
            data = SdkBytes.fromUtf8String(
                Jackson.defaultJsonMapper.writeValueAsString(
                    KinesisEvent("event-1", "orders", 1, "payload-1")
                )
            ),
            shardId = PRIMARY_SHARD,
        )
    }

    private fun service(
        client: LocalKinesisConsumerClient,
        leaseStore: KinesisLeaseStore,
    ): KinesisStreamService = KinesisStreamService(
        properties = KinesisWorkshopProperties(
            streamName = STREAM_NAME,
            partitionKey = "orders",
            consumerGroup = CONSUMER_GROUP,
            streamIdentity = STREAM_IDENTITY,
            maxShardConcurrency = 1,
        ),
        operations = LocalKinesisOperations(STREAM_NAME),
        objectMapper = Jackson.defaultJsonMapper,
        consumerClient = client,
        checkpointStore = InMemoryKinesisCheckpointStore(),
        leaseStore = leaseStore,
    )

    private fun consumerOptions(maxShardConcurrency: Int): KinesisConsumerOptions = KinesisConsumerOptions(
        ownerId = "consumer-test-owner",
        recordOptions = KinesisRecordFlowOptions(
            batchLimit = 1,
            pollInterval = 200.milliseconds,
            emptyBackoff = 200.milliseconds,
        ),
        maxShardConcurrency = maxShardConcurrency,
        discoveryInterval = 10.milliseconds,
    )

    private class RecordingCheckpointStore(
        shardIds: Set<String>,
        private val emitted: Set<String>,
    ) : KinesisCheckpointStore {
        private val delegate = InMemoryKinesisCheckpointStore()
        private val shardEnds = shardIds.associateWith { CompletableDeferred<Unit>() }

        override suspend fun load(key: KinesisShardKey): KinesisCheckpoint? = delegate.load(key)

        override suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease) {
            if (checkpoint is KinesisCheckpoint.Sequence) {
                check(checkpoint.sequenceNumber in emitted) {
                    "checkpoint ${checkpoint.sequenceNumber} was saved before downstream emit"
                }
            }
            delegate.save(key, checkpoint, lease)
            if (checkpoint is KinesisCheckpoint.ShardEnd) {
                shardEnds[key.shardId]?.complete(Unit)
            }
        }

        suspend fun awaitShardEnds() {
            withTimeout(5.seconds) {
                shardEnds.values.forEach { it.await() }
            }
        }
    }

    private class RecordingLeaseStore(
        private val delegate: KinesisLeaseStore,
    ) : KinesisLeaseStore {
        val events = CopyOnWriteArrayList<String>()
        val releasedKeys = ConcurrentHashMap.newKeySet<KinesisShardKey>()
        private val activeLeases = AtomicInteger()
        private val maxActiveLeases = AtomicInteger()

        val maxObservedActive: Int
            get() = maxActiveLeases.get()

        override suspend fun acquire(
            key: KinesisShardKey,
            ownerId: String,
            leaseDuration: kotlin.time.Duration,
        ): KinesisLease? = delegate.acquire(key, ownerId, leaseDuration).also {
            events += "acquire:${key.shardId}:${it?.ownerId}"
            if (it != null) {
                val active = activeLeases.incrementAndGet()
                maxActiveLeases.accumulateAndGet(active, ::maxOf)
            }
        }

        override suspend fun renew(
            lease: KinesisLease,
            leaseDuration: kotlin.time.Duration,
        ): KinesisLease? = delegate.renew(lease, leaseDuration)

        override suspend fun release(lease: KinesisLease) {
            events += "release:${lease.key.shardId}:${lease.ownerId}"
            releasedKeys += lease.key
            delegate.release(lease)
            activeLeases.decrementAndGet()
        }
    }

    private class MutableClock(initial: Instant) : Clock() {
        private val current = AtomicReference(initial)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current.get()

        fun advanceSeconds(seconds: Long) {
            current.updateAndGet { it.plusSeconds(seconds) }
        }
    }

    companion object {
        private const val STREAM_NAME = "orders"
        private const val CONSUMER_GROUP = "orders-consumer"
        private const val STREAM_IDENTITY = "orders-stream-v1"
        private const val PRIMARY_SHARD = "shardId-000000000000"
        private const val SECONDARY_SHARD = "shardId-000000000001"
    }
}
