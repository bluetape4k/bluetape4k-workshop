package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.TopicPartitionInfo
import org.apache.kafka.common.errors.TopicExistsException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

data class KafkaFailoverPartitionState(
    val partition: Int,
    val leader: Int?,
    val replicas: List<Int>,
    val isr: List<Int>,
)

data class KafkaFailoverClusterState(
    val nodes: List<Int>,
)

data class KafkaFailoverGroupState(
    val groupId: String,
    val coordinator: Int?,
    val generationId: Int?,
    val assignmentCount: Int,
    val assignedPartitions: Set<TopicPartition>,
)

/**
 * 제한된 AdminClient facade입니다. 모든 KafkaFuture는 현재 phase deadline으로
 * 기다리며 metadata/config/ISR의 일시 오류에는 공통 retry를 적용합니다.
 */
class KafkaFailoverAdmin(
    private val admin: Admin,
    private val retry: KafkaFailoverRetry = KafkaFailoverRetry(),
) : AutoCloseable {
    /** 이 facade가 기다린 KafkaFuture 연산 횟수입니다. */
    val adminRoundTripCount: Int
        get() = roundTripCount

    /** 이 facade가 관찰한 AdminClient 일시 오류 재시도 횟수입니다. */
    val retryCount: Int
        get() = retry.retryCount

    private var roundTripCount = 0

    fun createReferenceTopic(deadline: KafkaFailoverDeadline) {
        try {
            await(
                admin.createTopics(
                    listOf(
                        NewTopic(
                            KafkaFailoverTopology.TOPIC,
                            KafkaFailoverTopology.PARTITION_COUNT,
                            KafkaFailoverTopology.REPLICATION_FACTOR,
                        ).configs(
                            mapOf(
                                "min.insync.replicas" to KafkaFailoverTopology.MIN_INSYNC_REPLICAS.toString(),
                                "unclean.leader.election.enable" to "false",
                            ),
                        ),
                    ),
                ).all(),
                deadline,
                "topic-create",
            )
        } catch (error: Throwable) {
            if (!error.hasCause<TopicExistsException>()) throw error
        }
    }

    fun clusterState(deadline: KafkaFailoverDeadline): KafkaFailoverClusterState =
        retry.execute(deadline, "metadata") {
            KafkaFailoverClusterState(
                nodes = await(admin.describeCluster().nodes(), deadline, "metadata")
                    .map { it.id() }
                    .sorted(),
            )
        }.value

    fun topicState(topic: String = KafkaFailoverTopology.TOPIC, deadline: KafkaFailoverDeadline): List<KafkaFailoverPartitionState> =
        retry.execute(deadline, "metadata") {
            val descriptions = admin.describeTopics(listOf(topic)).topicNameValues()
            val description = await(descriptions[topic] ?: error("topic description unavailable"), deadline, "metadata")
            description.partitions().sortedBy(TopicPartitionInfo::partition).map(::toPartitionState)
        }.value

    fun partitionState(
        topic: String = KafkaFailoverTopology.TOPIC,
        partition: Int = KafkaFailoverTopology.PARTITION,
        deadline: KafkaFailoverDeadline,
    ): KafkaFailoverPartitionState = topicState(topic, deadline).first { it.partition == partition }

    fun groupState(groupId: String, deadline: KafkaFailoverDeadline): KafkaFailoverGroupState =
        retry.execute(deadline, "assignment") {
            val descriptions = admin.describeConsumerGroups(listOf(groupId)).describedGroups()
            val description = await(descriptions[groupId] ?: error("consumer group description unavailable"), deadline, "assignment")
            val assigned = description.members().flatMap { member ->
                member.assignment().topicPartitions().toList()
            }.toSet()
            KafkaFailoverGroupState(
                groupId = description.groupId(),
                coordinator = description.coordinator()?.id(),
                generationId = description.groupEpoch().orElse(null),
                assignmentCount = assigned.size,
                assignedPartitions = assigned,
            )
        }.value

    fun topicConfig(topic: String = KafkaFailoverTopology.TOPIC, deadline: KafkaFailoverDeadline): Map<String, String?> =
        retry.execute(deadline, "config") {
            val resource = ConfigResource(ConfigResource.Type.TOPIC, topic)
            val config = await(
                admin.describeConfigs(listOf(resource)).values()[resource]
                    ?: error("topic config unavailable"),
                deadline,
                "config",
            )
            config.entries().associate { entry -> entry.name() to entry.value() }
                .filterKeys { it in ALLOWLISTED_CONFIG_NAMES }
        }.value

    fun awaitPartition(
        topic: String,
        partition: Int,
        deadline: KafkaFailoverDeadline,
        predicate: (KafkaFailoverPartitionState) -> Boolean,
    ): KafkaFailoverPartitionState {
        var last: KafkaFailoverPartitionState? = null
        while (deadline.remainingNanos() > 0L) {
            val state = try {
                partitionState(topic, partition, deadline)
            } catch (error: Throwable) {
                if (!error.isTransientKafkaFailure()) throw error
                null
            }
            if (state != null) {
                last = state
                if (predicate(state)) return state
            }
            Thread.sleep(minOf(200L, (deadline.remainingNanos() / NANOS_PER_MILLI).coerceAtLeast(1L)))
        }
        throw java.util.concurrent.TimeoutException("partition wait deadline exhausted; last=$last")
    }

    fun awaitInternalTopic(
        topic: String,
        deadline: KafkaFailoverDeadline,
    ): List<KafkaFailoverPartitionState> = topicState(topic, deadline)

    override fun close() {
        admin.close(Duration.ofSeconds(5))
    }

    companion object {
        val ALLOWLISTED_CONFIG_NAMES: Set<String> = setOf(
            "min.insync.replicas",
            "unclean.leader.election.enable",
            "replication.factor",
            "num.partitions",
        )

        fun toPartitionState(info: TopicPartitionInfo): KafkaFailoverPartitionState =
            KafkaFailoverPartitionState(
                partition = info.partition(),
                leader = info.leader()?.id(),
                replicas = info.replicas().map { it.id() }.sorted(),
                isr = info.isr().map { it.id() }.sorted(),
            )

        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private fun <T> await(
        future: org.apache.kafka.common.KafkaFuture<T>,
        deadline: KafkaFailoverDeadline,
        phase: String,
    ): T = deadline.awaitBlocking(phase) {
        roundTripCount += 1
        val remaining = deadline.remainingNanos()
        if (remaining <= 0L) throw java.util.concurrent.TimeoutException("phase=$phase deadline exhausted")
        try {
            future.get(remaining, TimeUnit.NANOSECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.isTransientKafkaFailure(): Boolean =
        this is org.apache.kafka.common.errors.RetriableException ||
            this is org.apache.kafka.common.errors.DisconnectException ||
            this is org.apache.kafka.common.errors.TimeoutException ||
            cause?.isTransientKafkaFailure() == true
}
