package io.bluetape4k.workshop.aws.kinesis

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse
import software.amazon.awssdk.services.kinesis.model.ListShardsRequest
import software.amazon.awssdk.services.kinesis.model.ListShardsResponse
import software.amazon.awssdk.services.kinesis.model.Record
import software.amazon.awssdk.services.kinesis.model.SequenceNumberRange
import software.amazon.awssdk.services.kinesis.model.Shard
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType

/**
 * credential 없이 `consumerFlow`를 연습할 수 있는 결정적 Java SDK async client입니다.
 *
 * 기존 [LocalKinesisOperations]의 publish/ensure fake와 상태를 공유하지 않는 별도 adapter로,
 * `ListShards`는 두 개 이상의 shard를 노출하고 finite record episode를 순서대로 반환합니다.
 * 따라서 local profile은 AWS client나 네트워크를 만들지 않으면서 multi-shard discovery와
 * checkpoint/lease 계약을 그대로 실행할 수 있습니다.
 */
class LocalKinesisConsumerClient(
    private val configuredStreamName: String = "kinesis-coroutines-workshop",
    private val configuredShardId: String = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
    additionalShardIds: List<String> = listOf("shardId-000000000001"),
) : KinesisAsyncClient {

    private data class IteratorPosition(
        val shardId: String,
        val index: Int,
    )

    private val shardIds = (listOf(configuredShardId) + additionalShardIds)
        .distinct()
        .also { ids ->
            require(ids.size >= 2) { "local consumer fake requires at least two shards." }
            ids.forEach { id -> require(id.isNotBlank()) { "shard id must not be blank." } }
        }
    private val records = shardIds.associateWith { CopyOnWriteArrayList<Record>() }
    private val iterators = ConcurrentHashMap<String, IteratorPosition>()
    private val nextIterator = AtomicInteger()

    /** consumer discovery가 관찰할 deterministic shard 목록입니다. */
    val discoveredShardIds: List<String>
        get() = shardIds

    /** local fake는 공유 client lifecycle을 소유하지 않으므로 항상 닫히지 않은 상태입니다. */
    val closed: Boolean = false

    /** 기존 publish 결과를 consumer episode에 연결합니다. */
    fun append(
        sequenceNumber: String,
        partitionKey: String,
        data: SdkBytes,
        shardId: String = configuredShardId,
    ) {
        require(sequenceNumber.isNotBlank()) { "sequenceNumber must not be blank." }
        require(partitionKey.isNotBlank()) { "partitionKey must not be blank." }
        records.getValue(requireShard(shardId)) += Record.builder()
            .sequenceNumber(sequenceNumber)
            .partitionKey(partitionKey)
            .data(data)
            .approximateArrivalTimestamp(Instant.ofEpochMilli(sequenceNumber.toLongOrNull() ?: 0L))
            .build()
    }

    override fun serviceName(): String = "kinesis"

    override fun listShards(request: ListShardsRequest): CompletableFuture<ListShardsResponse> {
        requireStream(request.streamName())
        val response = ListShardsResponse.builder()
            .shards(shardIds.map { shardId ->
                Shard.builder()
                    .shardId(shardId)
                    .sequenceNumberRange(SequenceNumberRange.builder().build())
                    .build()
            })
            .build()
        return CompletableFuture.completedFuture(response)
    }

    override fun getShardIterator(request: GetShardIteratorRequest): CompletableFuture<GetShardIteratorResponse> {
        requireStream(request.streamName())
        val shardId = requireShard(request.shardId())
        val shardRecords = records.getValue(shardId)
        val index = when (request.shardIteratorType() ?: ShardIteratorType.TRIM_HORIZON) {
            ShardIteratorType.LATEST -> shardRecords.size
            ShardIteratorType.AT_SEQUENCE_NUMBER,
            ShardIteratorType.AFTER_SEQUENCE_NUMBER,
            -> indexForSequence(shardRecords, request.startingSequenceNumber(), request.shardIteratorType())
            ShardIteratorType.AT_TIMESTAMP -> indexForTimestamp(shardRecords, request.timestamp())
            else -> 0
        }
        val token = "local-${nextIterator.incrementAndGet()}"
        iterators[token] = IteratorPosition(shardId, index)
        return CompletableFuture.completedFuture(
            GetShardIteratorResponse.builder().shardIterator(token).build(),
        )
    }

    override fun getRecords(request: GetRecordsRequest): CompletableFuture<GetRecordsResponse> {
        val token = request.shardIterator()
            ?: return CompletableFuture.failedFuture(IllegalArgumentException("shardIterator must not be null."))
        val position = iterators[token]
            ?: return CompletableFuture.failedFuture(IllegalArgumentException("unknown local shard iterator."))
        val shardRecords = records.getValue(position.shardId)
        val limit = (request.limit() ?: 100).coerceIn(1, KinesisWorkshopProperties.MAX_BATCH_LIMIT)
        val end = (position.index + limit).coerceAtMost(shardRecords.size)
        val batch = if (position.index >= shardRecords.size) {
            emptyList()
        } else {
            shardRecords.subList(position.index, end).toList()
        }
        val nextToken = if (end < shardRecords.size) {
            "local-${nextIterator.incrementAndGet()}".also {
                iterators[it] = position.copy(index = end)
            }
        } else {
            null
        }
        return CompletableFuture.completedFuture(
            GetRecordsResponse.builder()
                .records(batch)
                .nextShardIterator(nextToken)
                .millisBehindLatest(0L)
                .build(),
        )
    }

    override fun close() = Unit

    private fun indexForSequence(
        shardRecords: List<Record>,
        sequenceNumber: String?,
        iteratorType: ShardIteratorType?,
    ): Int {
        val sequence = sequenceNumber?.toLongOrNull()
            ?: return shardRecords.size
        val index = shardRecords.indexOfFirst { it.sequenceNumber().toLongOrNull() == sequence }
        return when {
            index < 0 -> shardRecords.size
            iteratorType == ShardIteratorType.AFTER_SEQUENCE_NUMBER -> index + 1
            else -> index
        }
    }

    private fun indexForTimestamp(shardRecords: List<Record>, timestamp: Instant?): Int {
        val requested = timestamp ?: return 0
        return shardRecords.indexOfFirst { arrival ->
            !arrival.approximateArrivalTimestamp().isBefore(requested)
        }.let { index -> if (index >= 0) index else shardRecords.size }
    }

    private fun requireStream(streamName: String?) {
        require(streamName == configuredStreamName) { "local consumer fake supports one configured stream." }
    }

    private fun requireShard(shardId: String?): String {
        require(shardId in shardIds) { "local consumer fake does not know the requested shard." }
        return requireNotNull(shardId)
    }
}
