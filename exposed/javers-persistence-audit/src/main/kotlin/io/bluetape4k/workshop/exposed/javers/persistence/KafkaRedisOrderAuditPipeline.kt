package io.bluetape4k.workshop.exposed.javers.persistence

import io.bluetape4k.javers.persistence.kafka.projection.KafkaCdoSnapshotProjectionOptions
import io.bluetape4k.javers.persistence.kafka.projection.KafkaCdoSnapshotProjectionResult
import io.bluetape4k.javers.persistence.kafka.projection.KafkaCdoSnapshotProjector
import io.bluetape4k.javers.persistence.kafka.repository.VanillaKafkaCdoSnapshotRepository
import io.bluetape4k.javers.persistence.kafka.repository.VanillaKafkaCdoSnapshotRepositoryOptions
import io.bluetape4k.javers.persistence.redis.repository.LettuceCdoSnapshotRepository
import io.bluetape4k.javers.repository.CdoSnapshotRepository
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.RedisClient
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.javers.core.JaversBuilder
import org.javers.core.commit.Commit
import org.javers.core.diff.Diff
import org.javers.core.json.JsonConverter
import org.javers.core.metamodel.`object`.CdoSnapshot
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Kafka command side와 Redis query side를 분리하는 order audit projection 예제이다.
 *
 * 내부 Kafka repository는 write-only이므로 외부에 노출하지 않는다. Command는 Kafka snapshot stream에
 * 기록하고 audit history/latest는 Lettuce Redis repository, current row는 Exposed, diff는 memory에서 읽는다.
 * 공개 operation은 lock으로 직렬화되어 mutation과 catch-up이 서로 추월하지 않는다.
 */
class KafkaRedisOrderAuditPipeline internal constructor(
    private val writer: OrderAuditService,
    private val reader: OrderAuditService,
    private val projector: KafkaCdoSnapshotProjector,
    private val ownedResources: List<AutoCloseable>,
): AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val pendingProjection = AtomicBoolean(true)
    private val operationLock = ReentrantLock()

    fun place(author: String, order: Order) {
        mutate { writer.place(author, order) }
    }

    fun markPaid(author: String, orderId: String): Order {
        return mutate {
            requireProjectionCaughtUp(orderId)
            writer.markPaid(author, orderId)
        }
    }

    fun delete(author: String, orderId: String) {
        mutate {
            requireProjectionCaughtUp(orderId)
            writer.delete(author, orderId)
        }
    }

    fun findCurrent(orderId: String): Order? {
        return query { reader.findCurrent(orderId) }
    }

    @JvmOverloads
    fun getHistory(orderId: String, limit: Int = DEFAULT_HISTORY_LIMIT): List<CdoSnapshot> {
        return query { reader.getHistory(orderId, limit) }
    }

    fun getLatestSnapshot(orderId: String): CdoSnapshot? {
        return query { reader.getLatestSnapshot(orderId) }
    }

    fun diff(oldOrder: Order, newOrder: Order): Diff {
        return query { reader.diff(oldOrder, newOrder) }
    }

    @JvmOverloads
    fun replayUntilIdle(maxIdlePolls: Int = DEFAULT_MAX_IDLE_POLLS): KafkaCdoSnapshotProjectionResult {
        return operationLock.withLock {
            ensureOpen()
            projector.replayUntilIdle(maxIdlePolls).also {
                pendingProjection.set(false)
            }
        }
    }

    override fun close() {
        operationLock.withLock {
            if (!closed.compareAndSet(false, true)) {
                return
            }

            var firstFailure: Throwable? = null
            ownedResources.forEach { resource ->
                try {
                    resource.close()
                } catch (failure: Throwable) {
                    val previousFailure = firstFailure
                    if (previousFailure == null) {
                        firstFailure = failure
                    } else {
                        previousFailure.addSuppressed(failure)
                    }
                }
            }
            firstFailure?.let { throw it }
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "KafkaRedisOrderAuditPipeline is closed." }
    }

    private inline fun <T> mutate(block: () -> T): T =
        operationLock.withLock {
            ensureOpen()
            check(pendingProjection.compareAndSet(false, true)) {
                "Kafka snapshot projection must catch up before another mutation."
            }
            block()
        }

    private inline fun <T> query(block: () -> T): T =
        operationLock.withLock {
            ensureOpen()
            block()
        }

    private fun requireProjectionCaughtUp(orderId: String) {
        check(reader.getLatestSnapshot(orderId) != null) {
            "Redis projection must catch up before updating an audited order."
        }
    }

    private companion object {
        private const val DEFAULT_HISTORY_LIMIT = 100
        private const val DEFAULT_MAX_IDLE_POLLS = 3
    }
}

/**
 * dependencies 2.0.0이 관리하는 JaVers Kafka writer와 Lettuce Redis projector를 연결한다.
 *
 * [consumerConfigs]는 nonblank group id를 포함해야 한다. 수동 batch commit과 restart replay 계약을
 * 보존하기 위해 auto commit은 `false`, offset reset은 `earliest`만 허용한다. [redisClient]는 caller가
 * 소유하며 pipeline close 대상이 아니다.
 */
object KafkaRedisOrderAuditFactory {

    fun create(
        repositoryName: String,
        topic: String,
        producerConfigs: Map<String, Any?>,
        consumerConfigs: Map<String, Any?>,
        redisClient: RedisClient,
        pollTimeout: Duration = Duration.ofMillis(250),
    ): KafkaRedisOrderAuditPipeline {
        repositoryName.requireNotBlank("repositoryName")
        topic.requireNotBlank("topic")
        val validatedConsumerConfigs = consumerConfigs.validatedProjectionConfigs()

        val ownedResources = mutableListOf<AutoCloseable>()
        try {
            val readRepository = LettuceCdoSnapshotRepository(repositoryName, redisClient)
                .also(ownedResources::add)
            val writeRepository = VanillaKafkaCdoSnapshotRepository(
                producerConfigs = producerConfigs,
                options = VanillaKafkaCdoSnapshotRepositoryOptions(
                    topic = topic,
                    flushAfterSend = true,
                ),
            ).also(ownedResources::add)
            val commandRepository = KafkaPublishingCdoSnapshotRepository(
                publisher = writeRepository,
                queryRepository = readRepository,
            )
            val writer = OrderAuditService(
                JaversBuilder.javers()
                    .registerJaversRepository(commandRepository)
                    .build(),
            )

            val readJavers = JaversBuilder.javers()
                .registerJaversRepository(readRepository)
                .build()
            val reader = OrderAuditService(readJavers)

            val consumer = KafkaConsumer(
                validatedConsumerConfigs,
                StringDeserializer(),
                StringDeserializer(),
            ).also(ownedResources::add)
            val projector = KafkaCdoSnapshotProjector(
                consumer = consumer,
                jsonConverter = readJavers.jsonConverter,
                projectionRepository = readRepository,
                options = KafkaCdoSnapshotProjectionOptions(
                    topic = topic,
                    pollTimeout = pollTimeout,
                    closeConsumerOnClose = false,
                ),
            ).also(ownedResources::add)

            return KafkaRedisOrderAuditPipeline(
                writer = writer,
                reader = reader,
                projector = projector,
                ownedResources = ownedResources.asReversed(),
            )
        } catch (failure: Throwable) {
            ownedResources.asReversed().closeAfter(failure)
            throw failure
        }
    }
}

/**
 * JaVers의 commit 계산용 read/head는 Redis projection을 사용하고 persist만 Kafka publisher에 위임한다.
 *
 * 다음 command 전에 projection을 catch-up해야 version과 snapshot type이 이전 history를 이어간다.
 */
private class KafkaPublishingCdoSnapshotRepository(
    private val publisher: CdoSnapshotRepository,
    private val queryRepository: CdoSnapshotRepository,
): CdoSnapshotRepository by queryRepository {

    override fun setJsonConverter(jsonConverter: JsonConverter?) {
        queryRepository.setJsonConverter(jsonConverter)
        publisher.setJsonConverter(jsonConverter)
    }

    override fun persist(commit: Commit?) {
        publisher.persist(commit)
    }

    override fun saveSnapshot(snapshot: CdoSnapshot) {
        publisher.saveSnapshot(snapshot)
    }
}

private fun Map<String, Any?>.validatedProjectionConfigs(): Map<String, Any?> {
    val groupId = get(ConsumerConfig.GROUP_ID_CONFIG)?.toString()
    require(!groupId.isNullOrBlank()) {
        "consumerConfigs must contain a nonblank ${ConsumerConfig.GROUP_ID_CONFIG}."
    }

    val autoCommit = get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)?.toString()?.lowercase()
    require(autoCommit == null || autoCommit == "false") {
        "${ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG} must be false."
    }

    val offsetReset = get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)?.toString()?.lowercase()
    require(offsetReset == null || offsetReset == "earliest") {
        "${ConsumerConfig.AUTO_OFFSET_RESET_CONFIG} must be earliest."
    }

    return toMutableMap().apply {
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    }
}

private fun List<AutoCloseable>.closeAfter(firstFailure: Throwable) {
    forEach { resource ->
        try {
            resource.close()
        } catch (closeFailure: Throwable) {
            firstFailure.addSuppressed(closeFailure)
        }
    }
}
