package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.aws.spring.kinesis.KinesisOperations
import io.bluetape4k.aws.spring.kinesis.KinesisPutRecordRequest
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowRequest
import io.bluetape4k.aws.spring.kinesis.KinesisShardIteratorRequest
import io.bluetape4k.aws.spring.kinesis.KinesisStartingPosition
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import software.amazon.awssdk.services.kinesis.model.CreateStreamResponse
import software.amazon.awssdk.services.kinesis.model.DeleteStreamResponse
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse
import software.amazon.awssdk.services.kinesis.model.Record
import software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException
import software.amazon.awssdk.services.kinesis.model.Shard
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import software.amazon.awssdk.services.kinesis.model.StreamDescription
import software.amazon.awssdk.services.kinesis.model.StreamStatus

/**
 * `local` 프로필에서 사용하는 단일 스트림·단일 샤드 인메모리 구현입니다.
 *
 * 이 구현은 백그라운드 polling job이나 AWS client를 만들지 않습니다. 매 collection마다
 * trim-horizon부터 읽는 cold [Flow]를 제공하므로 순서와 cancellation 경계를 결정적으로
 * 확인할 수 있습니다.
 */
class LocalKinesisOperations(
    private val configuredStreamName: String = "kinesis-coroutines-workshop",
    private val configuredShardId: String = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
) : KinesisOperations {

    private val created = AtomicBoolean(false)
    private val nextSequence = AtomicLong(1)
    private val records = CopyOnWriteArrayList<Record>()
    private val getRecordsCounter = AtomicInteger()
    private val maxObservedBatch = AtomicInteger()
    private val maxObservedBatchBytesCounter = AtomicLong()

    /** 테스트가 실제 getRecords 호출 수를 확인할 수 있는 관찰 지점입니다. */
    val getRecordsCalls: Int
        get() = getRecordsCounter.get()

    /** 요청 limit을 넘는 prefetch가 없었는지 확인하는 관찰 지점입니다. */
    val maxObservedBatchSize: Int
        get() = maxObservedBatch.get()

    /** 한 번에 반환한 record payload bytes의 최대치입니다. */
    val maxObservedBatchBytes: Long
        get() = maxObservedBatchBytesCounter.get()

    /** local fake는 공유 AWS client를 소유하지 않으므로 항상 닫히지 않은 상태로 남습니다. */
    val closed: Boolean = false

    override suspend fun createStream(streamName: String, shardCount: Int): CreateStreamResponse {
        requireStream(streamName)
        require(shardCount == 1) { "local fake supports exactly one shard." }
        created.set(true)
        return CreateStreamResponse.builder().build()
    }

    override suspend fun createConfiguredStream(streamName: String): CreateStreamResponse =
        createStream(streamName, shardCount = 1)

    override suspend fun deleteStream(streamName: String): DeleteStreamResponse {
        requireStream(streamName)
        created.set(false)
        records.clear()
        nextSequence.set(1)
        return DeleteStreamResponse.builder().build()
    }

    override suspend fun describeStream(streamName: String): DescribeStreamResponse {
        requireStream(streamName)
        if (!created.get()) {
            throw ResourceNotFoundException.builder()
                .message("local stream does not exist")
                .build()
        }

        val description = StreamDescription.builder()
            .streamName(configuredStreamName)
            .streamStatus(StreamStatus.ACTIVE)
            .shards(Shard.builder().shardId(configuredShardId).build())
            .hasMoreShards(false)
            .build()
        return DescribeStreamResponse.builder().streamDescription(description).build()
    }

    override suspend fun putRecord(request: KinesisPutRecordRequest): PutRecordResponse {
        requireStream(request.streamName)
        checkCreated()
        require(request.partitionKey.isNotBlank()) { "partitionKey must not be blank." }

        val sequenceNumber = nextSequence.getAndIncrement().toString()
        val record = Record.builder()
            .sequenceNumber(sequenceNumber)
            .partitionKey(request.partitionKey)
            .data(request.data)
            .approximateArrivalTimestamp(Instant.ofEpochMilli(sequenceNumber.toLong()))
            .build()
        records += record
        return PutRecordResponse.builder()
            .sequenceNumber(sequenceNumber)
            .shardId(configuredShardId)
            .build()
    }

    override suspend fun putRecords(
        streamName: String,
        entries: List<PutRecordsRequestEntry>,
    ): PutRecordsResponse {
        require(entries.isNotEmpty()) { "entries must not be empty." }
        val responses = entries.map { entry ->
            putRecord(
                KinesisPutRecordRequest(
                    streamName = streamName,
                    partitionKey = entry.partitionKey(),
                    data = entry.data(),
                )
            )
        }
        return PutRecordsResponse.builder()
            .records(
                responses.map { response ->
                    software.amazon.awssdk.services.kinesis.model.PutRecordsResultEntry.builder()
                        .sequenceNumber(response.sequenceNumber())
                        .shardId(response.shardId())
                        .build()
                }
            )
            .failedRecordCount(0)
            .build()
    }

    override suspend fun getShardIterator(request: KinesisShardIteratorRequest): GetShardIteratorResponse {
        requireStream(request.streamName)
        checkCreated()
        require(request.shardId == configuredShardId) { "local fake supports one configured shard." }
        require(request.type != ShardIteratorType.AT_TIMESTAMP) {
            "local fake requires KinesisStartingPosition.AtTimestamp through recordFlow."
        }
        val index = when (request.type) {
            ShardIteratorType.LATEST -> records.size
            ShardIteratorType.AT_SEQUENCE_NUMBER,
            ShardIteratorType.AFTER_SEQUENCE_NUMBER,
            -> indexForSequence(request.startingSequenceNumber, request.type)
            else -> 0
        }
        return GetShardIteratorResponse.builder().shardIterator(iterator(index)).build()
    }

    override suspend fun getRecords(shardIterator: String, limit: Int): GetRecordsResponse {
        checkCreated()
        require(limit in 1..KinesisWorkshopProperties.MAX_BATCH_LIMIT) {
            "limit must be between 1 and ${KinesisWorkshopProperties.MAX_BATCH_LIMIT}."
        }
        getRecordsCounter.incrementAndGet()
        val index = parseIterator(shardIterator)
        val end = (index + limit).coerceAtMost(records.size)
        val batch = if (index >= records.size) emptyList() else records.subList(index, end).toList()
        maxObservedBatch.updateAndGet { observed -> maxOf(observed, batch.size) }
        maxObservedBatchBytesCounter.updateAndGet { observed ->
            maxOf(observed, batch.sumOf { it.data().asByteArray().size.toLong() })
        }
        return GetRecordsResponse.builder()
            .records(batch)
            .nextShardIterator(iterator(end))
            .millisBehindLatest(0L)
            .build()
    }

    override fun recordFlow(request: KinesisRecordFlowRequest): Flow<Record> = flow {
        request.streamName.requireConfigured()
        request.shardId.requireConfiguredShard()

        val position = request.position
        var iterator = if (position is KinesisStartingPosition.AtTimestamp) {
            iterator(indexForTimestamp(position.timestamp))
        } else {
            getShardIterator(
                KinesisShardIteratorRequest(
                    streamName = request.streamName,
                    shardId = request.shardId,
                    type = position.iteratorType(),
                    startingSequenceNumber = position.startingSequenceNumber(),
                )
            ).shardIterator()
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            val response = getRecords(iterator, request.options?.batchLimit ?: 100)
            response.records().forEach { record -> emit(record) }
            if (response.records().isEmpty()) return@flow
            iterator = response.nextShardIterator() ?: return@flow
        }
    }

    private fun indexForSequence(sequenceNumber: String?, type: ShardIteratorType): Int {
        val sequence = sequenceNumber?.toLongOrNull()
            ?: throw IllegalArgumentException("startingSequenceNumber must be numeric.")
        val index = records.indexOfFirst { it.sequenceNumber().toLongOrNull() == sequence }
        return when {
            index >= 0 && type == ShardIteratorType.AFTER_SEQUENCE_NUMBER -> index + 1
            index >= 0 -> index
            else -> records.size
        }
    }

    private fun indexForTimestamp(timestamp: Instant): Int =
        records.indexOfFirst { record ->
            !record.approximateArrivalTimestamp().isBefore(timestamp)
        }.let { index -> if (index >= 0) index else records.size }

    private fun parseIterator(shardIterator: String): Int {
        val parts = shardIterator.split(':')
        require(parts.size == 3 && parts[0] == configuredStreamName && parts[1] == configuredShardId) {
            "invalid local shard iterator."
        }
        return parts[2].toIntOrNull() ?: throw IllegalArgumentException("invalid local shard iterator.")
    }

    private fun iterator(index: Int): String = "$configuredStreamName:$configuredShardId:$index"

    private fun requireStream(streamName: String) {
        require(streamName == configuredStreamName) { "local fake supports one configured stream." }
    }

    private fun checkCreated() {
        if (!created.get()) {
            throw ResourceNotFoundException.builder().message("local stream does not exist").build()
        }
    }

    private fun String.requireConfigured() = require(this == configuredStreamName) {
        "local fake supports one configured stream."
    }

    private fun String.requireConfiguredShard() = require(this == configuredShardId) {
        "local fake supports one configured shard."
    }

    private fun KinesisStartingPosition.iteratorType(): ShardIteratorType = when (this) {
        KinesisStartingPosition.TrimHorizon -> ShardIteratorType.TRIM_HORIZON
        KinesisStartingPosition.Latest -> ShardIteratorType.LATEST
        is KinesisStartingPosition.AtSequenceNumber -> ShardIteratorType.AT_SEQUENCE_NUMBER
        is KinesisStartingPosition.AfterSequenceNumber -> ShardIteratorType.AFTER_SEQUENCE_NUMBER
        is KinesisStartingPosition.AtTimestamp -> ShardIteratorType.AT_TIMESTAMP
    }

    private fun KinesisStartingPosition.startingSequenceNumber(): String? = when (this) {
        is KinesisStartingPosition.AtSequenceNumber -> sequenceNumber
        is KinesisStartingPosition.AfterSequenceNumber -> sequenceNumber
        else -> null
    }
}
