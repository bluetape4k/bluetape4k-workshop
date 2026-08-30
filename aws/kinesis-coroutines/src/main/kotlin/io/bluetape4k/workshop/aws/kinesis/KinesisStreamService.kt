package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.aws.kinesis.InMemoryKinesisCheckpointStore
import io.bluetape4k.aws.kinesis.InMemoryKinesisLeaseStore
import io.bluetape4k.aws.kinesis.KinesisCheckpointStore
import io.bluetape4k.aws.kinesis.KinesisConsumerOptions
import io.bluetape4k.aws.kinesis.KinesisLease
import io.bluetape4k.aws.kinesis.KinesisLeaseStore
import io.bluetape4k.aws.kinesis.KinesisShardKey
import io.bluetape4k.aws.kinesis.KinesisRecordFlowOptions as ConsumerRecordFlowOptions
import io.bluetape4k.aws.kinesis.KinesisStartingPosition as ConsumerStartingPosition
import io.bluetape4k.aws.kinesis.consumerFlow
import io.bluetape4k.aws.spring.kinesis.KinesisOperations
import io.bluetape4k.aws.spring.kinesis.KinesisPutRecordRequest
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowOptions
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowRequest
import io.bluetape4k.aws.spring.kinesis.KinesisStartingPosition
import io.bluetape4k.jackson3.Jackson
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException
import software.amazon.awssdk.services.kinesis.model.ResourceInUseException
import software.amazon.awssdk.services.kinesis.model.StreamStatus
import tools.jackson.databind.json.JsonMapper

/** Kinesis stream readiness, JSON 변환, publish/consume 경계를 한 곳에 둡니다. */
class KinesisStreamService(
    private val properties: KinesisWorkshopProperties,
    private val operations: KinesisOperations,
    private val objectMapper: JsonMapper = Jackson.defaultJsonMapper,
    private val flowOptions: KinesisRecordFlowOptions = properties.toFlowOptions(),
    private val demoScope: KinesisDemoScope? = null,
    private val consumerClient: KinesisAsyncClient? = null,
    private val checkpointStore: KinesisCheckpointStore = InMemoryKinesisCheckpointStore(),
    private val leaseStore: KinesisLeaseStore = InMemoryKinesisLeaseStore(),
) {

    private val activeCollectors = AtomicInteger()

    /** 현재 collection 중인 caller-owned collector 수입니다. 작업을 시작하지 않습니다. */
    val activeCollectorCount: Int
        get() = activeCollectors.get()

    /** stream이 없을 때만 생성하고 ACTIVE가 될 때까지 bounded polling 합니다. */
    suspend fun ensureStream(): KinesisStreamReadiness = withTimeout(properties.readinessTimeout.toMillis()) {
        var description = try {
            operations.describeStream(properties.streamName).streamDescription()
        } catch (_: ResourceNotFoundException) {
            try {
                operations.createStream(properties.streamName, shardCount = 1)
            } catch (_: ResourceInUseException) {
                // 다른 caller가 먼저 생성한 경우 describe로 readiness를 재확인합니다.
            }
            operations.describeStream(properties.streamName).streamDescription()
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            when (description.streamStatus()) {
                StreamStatus.ACTIVE -> break
                StreamStatus.CREATING -> {
                    delay(properties.readinessPollInterval.toMillis())
                    description = operations.describeStream(properties.streamName).streamDescription()
                }
                else -> return@withTimeout KinesisStreamReadiness(KinesisStreamStatus.FAILED)
            }
        }
        KinesisStreamReadiness(KinesisStreamStatus.ACTIVE)
    }

    /** 이벤트를 JSON으로 직렬화해 upstream putRecord에 전달합니다. */
    suspend fun publish(event: KinesisEvent): KinesisPublishReport {
        val payload = objectMapper.writeValueAsString(event)
        require(payload.toByteArray(Charsets.UTF_8).size <= properties.maxAggregatePayloadBytes) {
            "event payload exceeds the configured size bound."
        }
        val response = operations.putRecord(
            KinesisPutRecordRequest(
                streamName = properties.streamName,
                partitionKey = event.partitionKey,
                data = SdkBytes.fromUtf8String(payload),
            )
        )
        (consumerClient as? LocalKinesisConsumerClient)?.append(
            sequenceNumber = response.sequenceNumber(),
            partitionKey = event.partitionKey,
            data = SdkBytes.fromUtf8String(payload),
            shardId = response.shardId(),
        )
        return KinesisPublishReport(
            ordinal = event.ordinal,
            sequenceNumber = response.sequenceNumber(),
            shardId = response.shardId(),
        )
    }

    /** caller가 collection할 때만 upstream cold Flow를 구독합니다. */
    fun consume(position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon): Flow<KinesisConsumedRecord> {
        val source = consumerClient?.let { client ->
            flow {
                val trackedLeaseStore = TrackingKinesisLeaseStore(leaseStore)
                try {
                    client.consumerFlow(
                        streamName = properties.streamName,
                        consumerGroup = properties.consumerGroup,
                        streamIdentity = properties.streamIdentity,
                        position = position.toConsumerPosition(),
                        options = properties.toConsumerOptions(),
                        checkpointStore = checkpointStore,
                        leaseStore = trackedLeaseStore,
                    ).collect { emit(it.record) }
                } finally {
                    withContext(NonCancellable) { trackedLeaseStore.releaseAll() }
                }
            }
        } ?: operations.recordFlow(
            KinesisRecordFlowRequest(
                streamName = properties.streamName,
                shardId = properties.shardId,
                position = position,
                options = flowOptions,
            )
        )
        var collectorJob: Job? = null
        return source
            .onStart {
                activeCollectors.incrementAndGet()
                collectorJob = currentCoroutineContext()[Job]
                collectorJob?.let {
                    if (demoScope?.registerCallerCollector(it) == false) {
                        throw CancellationException("Kinesis collector admission is closed.")
                    }
                }
            }
            .map { record ->
                currentCoroutineContext().ensureActive()
                KinesisConsumedRecord(
                    sequenceNumber = record.sequenceNumber(),
                    partitionKey = record.partitionKey(),
                    event = objectMapper.readValue(record.data().asUtf8String(), KinesisEvent::class.java),
                )
            }
            .onCompletion {
                activeCollectors.decrementAndGet()
                collectorJob?.let { demoScope?.unregisterCallerCollector(it) }
                collectorJob = null
            }
    }

    /** upstream cancellation 경계에서도 이 collection이 획득한 lease를 회수합니다. */
    private class TrackingKinesisLeaseStore(
        private val delegate: KinesisLeaseStore,
    ) : KinesisLeaseStore {
        private val activeLeases = ConcurrentHashMap<KinesisShardKey, KinesisLease>()

        override suspend fun acquire(
            key: KinesisShardKey,
            ownerId: String,
            leaseDuration: kotlin.time.Duration,
        ): KinesisLease? = delegate.acquire(key, ownerId, leaseDuration)?.also { lease ->
            activeLeases[key] = lease
        }

        override suspend fun renew(
            lease: KinesisLease,
            leaseDuration: kotlin.time.Duration,
        ): KinesisLease? = delegate.renew(lease, leaseDuration)?.also { renewed ->
            activeLeases[renewed.key] = renewed
        } ?: run {
            activeLeases.remove(lease.key, lease)
            null
        }

        override suspend fun release(lease: KinesisLease) {
            activeLeases.remove(lease.key, lease)
            delegate.release(lease)
        }

        suspend fun releaseAll() {
            activeLeases.values.toList().forEach { lease ->
                if (activeLeases.remove(lease.key, lease)) delegate.release(lease)
            }
        }
    }

    private companion object {
        fun KinesisWorkshopProperties.toFlowOptions(): KinesisRecordFlowOptions = KinesisRecordFlowOptions(
            batchLimit = batchLimit,
            pollInterval = pollInterval,
            emptyBackoff = emptyBackoff,
            maxIteratorRetries = maxIteratorRetries,
            maxThrottleRetries = maxThrottleRetries,
        )

        fun KinesisWorkshopProperties.toConsumerOptions(): KinesisConsumerOptions = KinesisConsumerOptions(
            ownerId = ownerId,
            recordOptions = ConsumerRecordFlowOptions(
                batchLimit = batchLimit,
                pollInterval = pollInterval.toMillis().milliseconds,
                emptyBackoff = emptyBackoff.toMillis().milliseconds,
                maxIteratorRetries = maxIteratorRetries,
                maxThrottleRetries = maxThrottleRetries,
            ),
            maxShardConcurrency = maxShardConcurrency,
            discoveryInterval = 100.milliseconds,
        )

        fun KinesisStartingPosition.toConsumerPosition(): ConsumerStartingPosition = when (this) {
            KinesisStartingPosition.TrimHorizon -> ConsumerStartingPosition.TrimHorizon
            KinesisStartingPosition.Latest -> ConsumerStartingPosition.Latest
            is KinesisStartingPosition.AtSequenceNumber ->
                ConsumerStartingPosition.AtSequenceNumber(sequenceNumber)
            is KinesisStartingPosition.AfterSequenceNumber ->
                ConsumerStartingPosition.AfterSequenceNumber(sequenceNumber)
            is KinesisStartingPosition.AtTimestamp -> ConsumerStartingPosition.AtTimestamp(timestamp)
        }
    }
}
