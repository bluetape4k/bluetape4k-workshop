package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.aws.spring.kinesis.KinesisPutRecordRequest
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowOptions
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowRequest
import io.bluetape4k.aws.spring.kinesis.KinesisShardIteratorRequest
import io.bluetape4k.aws.spring.kinesis.KinesisStartingPosition
import io.bluetape4k.junit5.coroutines.runSuspendIO
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import software.amazon.awssdk.services.kinesis.model.StreamStatus

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalKinesisOperationsTest {

    @Test
    fun `creates one deterministic active stream idempotently`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")

        assertFailsWith<ResourceNotFoundException> {
            operations.describeStream("orders")
        }

        operations.createStream("orders", shardCount = 1)
        operations.createStream("orders", shardCount = 1)

        val description = operations.describeStream("orders").streamDescription()
        description.streamStatus() shouldBeEqualTo StreamStatus.ACTIVE
        description.shards().single().shardId() shouldBeEqualTo KinesisWorkshopProperties.DEFAULT_SHARD_ID
    }

    @Test
    fun `preserves partition key and append order`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        operations.createStream("orders", shardCount = 1)

        val first = operations.putRecord(record("orders", "same-key", "first"))
        val second = operations.putRecord(record("orders", "same-key", "second"))

        first.sequenceNumber().shouldNotBeEmpty()
        second.sequenceNumber().shouldNotBeEmpty()
        second.sequenceNumber().toLong() shouldBeEqualTo first.sequenceNumber().toLong() + 1

        val records = operations.recordFlow(
            KinesisRecordFlowRequest(
                streamName = "orders",
                shardId = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
                position = KinesisStartingPosition.TrimHorizon,
            )
        ).toList()

        records.map { it.data().asUtf8String() } shouldBeEqualTo listOf("first", "second")
        records.map { it.partitionKey() } shouldBeEqualTo listOf("same-key", "same-key")
    }

    @Test
    fun `get records honours limit without prefetching`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        operations.createStream("orders", shardCount = 1)
        repeat(3) { index -> operations.putRecord(record("orders", "key-$index", "payload-$index")) }

        val iterator = operations.getShardIterator(
            KinesisShardIteratorRequest(
                streamName = "orders",
                shardId = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
                type = ShardIteratorType.TRIM_HORIZON,
            )
        ).shardIterator()

        val firstBatch = operations.getRecords(iterator, limit = 2)
        firstBatch.records().size shouldBeEqualTo 2
        firstBatch.nextShardIterator() shouldBeEqualTo "orders:${KinesisWorkshopProperties.DEFAULT_SHARD_ID}:2"

        val secondBatch = operations.getRecords(firstBatch.nextShardIterator(), limit = 2)
        secondBatch.records().size shouldBeEqualTo 1
        operations.getRecordsCalls shouldBeEqualTo 2
    }

    @Test
    fun `record flow is cold and take cancellation does not close shared fake`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        operations.createStream("orders", shardCount = 1)
        repeat(3) { index -> operations.putRecord(record("orders", "key", "payload-$index")) }
        val request = KinesisRecordFlowRequest(
            streamName = "orders",
            shardId = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
            position = KinesisStartingPosition.TrimHorizon,
            options = KinesisRecordFlowOptions(batchLimit = 1),
        )

        val first = operations.recordFlow(request).take(1).first()
        first.data().asUtf8String() shouldBeEqualTo "payload-0"

        val replay = operations.recordFlow(request).toList()
        replay.size shouldBeEqualTo 3
        operations.closed shouldBeEqualTo false
    }

    @Test
    fun `slow collector never observes a batch beyond configured limit`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        operations.createStream("orders", shardCount = 1)
        repeat(5) { index -> operations.putRecord(record("orders", "key", "payload-$index")) }

        operations.recordFlow(
            KinesisRecordFlowRequest(
                streamName = "orders",
                shardId = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
                position = KinesisStartingPosition.TrimHorizon,
                options = KinesisRecordFlowOptions(batchLimit = 2),
            )
        ).onEach { kotlinx.coroutines.delay(1) }.toList()

        (operations.maxObservedBatchSize <= 2) shouldBeEqualTo true
        (operations.maxObservedBatchBytes <= KinesisWorkshopProperties.MAX_AGGREGATE_PAYLOAD_BYTES) shouldBeEqualTo true
    }

    @Test
    fun `at timestamp starts at the first record at or after timestamp`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        operations.createStream("orders", shardCount = 1)
        repeat(3) { index -> operations.putRecord(record("orders", "key", "payload-$index")) }

        val records = operations.recordFlow(
            KinesisRecordFlowRequest(
                streamName = "orders",
                shardId = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
                position = KinesisStartingPosition.AtTimestamp(Instant.ofEpochMilli(2)),
                options = KinesisRecordFlowOptions(batchLimit = 10),
            )
        ).toList()

        records.map { it.sequenceNumber() } shouldBeEqualTo listOf("2", "3")
    }

    @Test
    fun `direct at timestamp shard iterator is rejected instead of silently using trim horizon`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        operations.createStream("orders", shardCount = 1)

        assertFailsWith<IllegalArgumentException> {
            operations.getShardIterator(
                KinesisShardIteratorRequest(
                    streamName = "orders",
                    shardId = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
                    type = ShardIteratorType.AT_TIMESTAMP,
                )
            )
        }
    }

    private fun record(streamName: String, partitionKey: String, payload: String): KinesisPutRecordRequest =
        KinesisPutRecordRequest(
            streamName = streamName,
            partitionKey = partitionKey,
            data = SdkBytes.fromUtf8String(payload),
        )
}
