package io.bluetape4k.workshop.aws.ktordynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.describeTable
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsRecordFlowOptions
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsShardRecord
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsStartingPosition
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsCheckpointStore
import io.bluetape4k.aws.kotlin.dynamodbstreams.InMemoryDynamoDbStreamsCheckpointStore
import io.bluetape4k.aws.kotlin.dynamodbstreams.shardRecordFlow
import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 기존 order-session 테이블의 DynamoDB Streams를 bounded coroutine Flow로 소비하는 Ktor 서비스입니다.
 *
 * [DynamoDbStreamsShardRecord]가 collector에 전달되고 처리 함수가 반환된 뒤에만 upstream이
 * checkpoint를 저장합니다. 따라서 중단 시 마지막 record가 재전달될 수 있는 at-least-once 경계를
 * 직접 확인할 수 있습니다. client는 애플리케이션이 소유하며 [close]에서 한 번만 닫습니다.
 */
internal class DynamoDbStreamsOrderSessionService(
    private val dynamoDbClient: DynamoDbClient,
    private val streamsClient: DynamoDbStreamsClient,
    private val tableName: String,
    private val config: DynamoDbStreamsWorkshopConfig,
    private val checkpointStore: DynamoDbStreamsCheckpointStore = InMemoryDynamoDbStreamsCheckpointStore(),
) : AutoCloseable {

    private val delivered = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)

    /** 마지막 소비 결과의 sequence와 checkpoint를 HTTP 응답으로 표현합니다. */
    suspend fun consume(
        maxRecords: Int? = null,
        position: DynamoDbStreamsStartingPosition? = null,
        process: suspend (DynamoDbStreamsShardRecord) -> Unit = {},
    ): DynamoDbStreamsConsumptionReport {
        check(!closed.get()) { "DynamoDB Streams service is closed." }
        val effectiveMaxRecords = maxRecords ?: config.maxRecords
        val effectivePosition = position ?: config.startingPosition
        require(effectiveMaxRecords in 1..DynamoDbStreamsRecordFlowOptions.MAX_BATCH_LIMIT) {
            "maxRecords must be in 1..${DynamoDbStreamsRecordFlowOptions.MAX_BATCH_LIMIT}."
        }

        val streamArn = resolveStreamArn()
        val records = mutableListOf<DynamoDbStreamsRecordSummary>()
        streamsClient.shardRecordFlow(
            streamArn = streamArn,
            position = effectivePosition,
            options = config.flowOptions,
            checkpointStore = checkpointStore,
        ).take(effectiveMaxRecords).collect { envelope ->
            currentCoroutineContext().ensureActive()
            val sequenceNumber = envelope.record.dynamodb?.sequenceNumber
                ?: error("DynamoDB Streams record did not contain a sequence number.")
            val deliveryKey = "${envelope.shardId}\u0000$sequenceNumber"
            val duplicate = !delivered.add(deliveryKey)

            // process가 성공적으로 반환되기 전에는 checkpoint가 저장되지 않습니다.
            process(envelope)
            // take 경계에서 upstream이 취소되어도 성공한 마지막 record를 잃지 않도록
            // 소비자 경계에서도 같은 checkpoint를 명시적으로 저장합니다.
            checkpointStore.save(streamArn, envelope.shardId, sequenceNumber)
            records += DynamoDbStreamsRecordSummary(
                shardId = envelope.shardId,
                sequenceNumber = sequenceNumber,
                keyId = envelope.record.dynamodb?.keys?.get("id")?.asS(),
                duplicate = duplicate,
            )
        }

        val checkpointByShard = records.asSequence()
            .map { it.shardId }
            .distinct()
            .associateWith { shardId -> checkNotNull(checkpointStore.load(streamArn, shardId)) }

        return DynamoDbStreamsConsumptionReport(
            streamArn = streamArn,
            startingPosition = effectivePosition.label(),
            processed = records,
            duplicateSequenceNumbers = records.filter { it.duplicate }.map { it.sequenceNumber },
            checkpointByShard = checkpointByShard,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            streamsClient.close()
        }
    }

    private suspend fun resolveStreamArn(): String =
        dynamoDbClient.describeTable { tableName = this@DynamoDbStreamsOrderSessionService.tableName }
            .table
            ?.latestStreamArn
            ?.takeIf { it.isNotBlank() }
            ?: throw DynamoDbUnavailableException(
                "DynamoDB table '${tableName}' has no enabled Streams ARN.",
            )

    private fun DynamoDbStreamsStartingPosition.label(): String = when (this) {
        DynamoDbStreamsStartingPosition.TrimHorizon -> "trim_horizon"
        DynamoDbStreamsStartingPosition.Latest -> "latest"
        is DynamoDbStreamsStartingPosition.AtSequenceNumber -> "at_sequence_number:$sequenceNumber"
        is DynamoDbStreamsStartingPosition.AfterSequenceNumber -> "after_sequence_number:$sequenceNumber"
    }
}

/** 한 번의 Flow 수집에서 외부에 노출해도 안전한 record 요약입니다. */
@kotlinx.serialization.Serializable
internal data class DynamoDbStreamsRecordSummary(
    val shardId: String,
    val sequenceNumber: String,
    val keyId: String?,
    val duplicate: Boolean,
)

/** bounded consume 호출의 결과입니다. payload와 AWS credential은 포함하지 않습니다. */
@kotlinx.serialization.Serializable
internal data class DynamoDbStreamsConsumptionReport(
    val streamArn: String,
    val startingPosition: String,
    val processed: List<DynamoDbStreamsRecordSummary>,
    val duplicateSequenceNumbers: List<String>,
    val checkpointByShard: Map<String, String>,
)
