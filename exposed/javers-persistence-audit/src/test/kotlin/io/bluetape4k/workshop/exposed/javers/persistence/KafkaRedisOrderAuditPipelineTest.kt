package io.bluetape4k.workshop.exposed.javers.persistence

import com.google.gson.JsonObject
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javers.codecs.JaversCodecs
import io.bluetape4k.javers.persistence.kafka.projection.KafkaCdoSnapshotProjectionOptions
import io.bluetape4k.javers.persistence.kafka.projection.KafkaCdoSnapshotProjector
import io.bluetape4k.javers.persistence.redis.repository.LettuceCdoSnapshotRepository
import io.bluetape4k.javers.repository.CdoSnapshotRepository
import io.bluetape4k.testcontainers.mq.KafkaServer
import io.bluetape4k.testcontainers.storage.RedisServer
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.Node
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.javers.core.JaversBuilder
import org.javers.core.metamodel.`object`.CdoSnapshot
import org.javers.core.metamodel.`object`.SnapshotType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class KafkaRedisOrderAuditPipelineTest {

    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        deleteProjectionKeys()
        database = Database.connect(
            url = "jdbc:h2:mem:kafka-redis-order-audit-${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.create(OrderTable)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(database) {
            SchemaUtils.drop(OrderTable)
        }
        deleteProjectionKeys()
    }

    @Test
    fun `Kafka snapshot stream is projected to queryable Redis history`() {
        val topic = uniqueName("orders")
        val repositoryName = repositoryName("primary")
        val pipeline = pipeline(topic, repositoryName, uniqueName("primary-group"))

        pipeline.use {
            val order = sampleOrder("order-100")
            it.replayUntilIdle()
            it.place("alice", order)
            it.replayUntilIdle().projectedSnapshots shouldBeEqualTo 1
            it.markPaid("bob", order.id)

            val replay = it.replayUntilIdle()

            replay.projectedSnapshots shouldBeEqualTo 1
            replay.skippedSnapshots shouldBeEqualTo 0
            it.getHistory(order.id, 2).map { snapshot -> snapshot.type } shouldBeEqualTo
                listOf(SnapshotType.UPDATE, SnapshotType.INITIAL)
            it.getLatestSnapshot(order.id)
                .shouldNotBeNull()
                .getPropertyValue("status") shouldBeEqualTo OrderStatus.PAID
        }
    }

    @Test
    fun `a published command blocks every later mutation until projection catches up`() {
        val pipeline = pipeline(
            topic = uniqueName("pending-command"),
            repositoryName = repositoryName("pending-command"),
            groupId = uniqueName("pending-command-group"),
        )

        pipeline.use {
            val order = sampleOrder("order-pending-command")
            it.replayUntilIdle()
            it.place("alice", order)

            assertFailsWith<IllegalStateException> {
                it.place("alice", sampleOrder("another-order"))
            }.message shouldBeEqualTo "Kafka snapshot projection must catch up before another mutation."
            assertFailsWith<IllegalStateException> {
                it.markPaid("bob", order.id)
            }.message shouldBeEqualTo "Kafka snapshot projection must catch up before another mutation."

            it.replayUntilIdle().projectedSnapshots shouldBeEqualTo 1
            it.markPaid("bob", order.id)
        }
    }

    @Test
    fun `a restarted pipeline catches up existing Kafka backlog before accepting a mutation`() {
        val topic = uniqueName("restart-backlog")
        val repositoryName = repositoryName("restart-backlog")
        val groupId = uniqueName("restart-backlog-group")
        val order = sampleOrder("order-restart-backlog")

        pipeline(topic, repositoryName, groupId).use {
            it.replayUntilIdle()
            it.place("alice", order)
            it.replayUntilIdle()
            it.markPaid("bob", order.id)
        }

        pipeline(topic, repositoryName, groupId).use {
            assertFailsWith<IllegalStateException> {
                it.delete("carol", order.id)
            }.message shouldBeEqualTo "Kafka snapshot projection must catch up before another mutation."

            it.replayUntilIdle().projectedSnapshots shouldBeEqualTo 1
            it.delete("carol", order.id)
        }
    }

    @Test
    fun `new consumer group skips duplicate snapshots and restart rebuilds another Redis projection`() {
        val topic = uniqueName("replay")
        val order = sampleOrder("order-200")
        val sourceRepository = repositoryName("source")

        pipeline(topic, sourceRepository, uniqueName("first-group")).use {
            it.replayUntilIdle()
            it.place("alice", order)
            it.replayUntilIdle().projectedSnapshots shouldBeEqualTo 1
            it.markPaid("bob", order.id)
            it.replayUntilIdle().projectedSnapshots shouldBeEqualTo 1
        }

        pipeline(topic, sourceRepository, uniqueName("duplicate-group")).use {
            val duplicate = it.replayUntilIdle()
            duplicate.projectedSnapshots shouldBeEqualTo 0
            duplicate.skippedSnapshots shouldBeEqualTo 2
            it.getHistory(order.id) shouldHaveSize 2
        }

        pipeline(topic, repositoryName("rebuilt"), uniqueName("restart-group")).use {
            val rebuilt = it.replayUntilIdle()
            rebuilt.projectedSnapshots shouldBeEqualTo 2
            it.getHistory(order.id, 1).single().type shouldBeEqualTo SnapshotType.UPDATE
        }
    }

    @Test
    fun `invalid projection consumer contract is rejected before resources are created`() {
        val valid = consumerConfigs(uniqueName("valid-group"))

        listOf(
            valid - ConsumerConfig.GROUP_ID_CONFIG,
            valid + (ConsumerConfig.GROUP_ID_CONFIG to " "),
            valid + (ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to true),
            valid + (ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest"),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                KafkaRedisOrderAuditFactory.create(
                    repositoryName = repositoryName("invalid"),
                    topic = uniqueName("invalid"),
                    producerConfigs = producerConfigs(),
                    consumerConfigs = invalid,
                    redisClient = lettuceClient,
                    pollTimeout = Duration.ofMillis(250),
                )
            }
        }
    }

    @Test
    fun `multi partition topic is rejected before polling or changing Redis head`() {
        val topic = uniqueName("multi-partition")
        val consumer = MockConsumer<String, String>("earliest")
        consumer.updatePartitions(topic, listOf(partitionInfo(topic, 0), partitionInfo(topic, 1)))
        val pollCount = AtomicInteger()
        consumer.schedulePollTask { pollCount.incrementAndGet() }
        val repository = LettuceCdoSnapshotRepository(repositoryName("multi-partition"), lettuceClient)
        val javers = JaversBuilder.javers().registerJaversRepository(repository).build()

        val projector = KafkaCdoSnapshotProjector(
            consumer = consumer,
            jsonConverter = javers.jsonConverter,
            projectionRepository = repository,
            options = KafkaCdoSnapshotProjectionOptions(topic = topic),
        )

        try {
            assertFailsWith<IllegalStateException> {
                projector.projectOnce()
            }
            pollCount.get() shouldBeEqualTo 0
            repository.getHeadId() shouldBeEqualTo null
        } finally {
            consumer.close()
            repository.close()
        }
    }

    @Test
    fun `replay waits through an initial empty poll before projecting a record`() {
        val topic = uniqueName("initial-empty")
        val topicPartition = TopicPartition(topic, 0)
        val consumer = MockConsumer<String, String>("earliest")
        consumer.updatePartitions(topic, listOf(partitionInfo(topic, 0)))
        consumer.schedulePollTask { }
        consumer.schedulePollTask {
            consumer.rebalance(listOf(topicPartition))
            consumer.updateBeginningOffsets(mapOf(topicPartition to 0L))
            consumer.addRecord(
                ConsumerRecord(topic, 0, 0L, "order-key", encodedSnapshot("order-empty-poll")),
            )
        }
        val repository = LettuceCdoSnapshotRepository(repositoryName("initial-empty"), lettuceClient)
        val javers = JaversBuilder.javers().registerJaversRepository(repository).build()
        val projector = KafkaCdoSnapshotProjector(
            consumer = consumer,
            jsonConverter = javers.jsonConverter,
            projectionRepository = repository,
            options = KafkaCdoSnapshotProjectionOptions(topic = topic),
        )

        try {
            val result = projector.replayUntilIdle(maxIdlePolls = 3)

            result.projectedSnapshots shouldBeEqualTo 1
            repository.getHeadId().shouldNotBeNull()
        } finally {
            consumer.close()
            repository.close()
        }
    }

    @Test
    fun `failed batch is replayed by a new consumer in the same group before offsets advance`() {
        val topic = uniqueName("retry")
        val order = sampleOrder("order-retry")
        pipeline(topic, repositoryName("retry-source"), uniqueName("source-group")).use {
            it.replayUntilIdle()
            it.place("alice", order)
            it.replayUntilIdle().projectedSnapshots shouldBeEqualTo 1
            it.markPaid("bob", order.id)
            it.replayUntilIdle().projectedSnapshots shouldBeEqualTo 1
        }

        val groupId = uniqueName("retry-group")
        val target = LettuceCdoSnapshotRepository(repositoryName("retry-target"), lettuceClient)
        val targetJavers = JaversBuilder.javers().registerJaversRepository(target).build()
        val targetService = OrderAuditService(targetJavers)
        val failingTarget = FailOnSecondProjectionRepository(target)
        val failedProjector = projector(topic, groupId, targetJavers, failingTarget)

        try {
            var observedFailure: IllegalStateException? = null
            repeat(20) {
                if (observedFailure == null) {
                    try {
                        failedProjector.projectOnce()
                    } catch (failure: IllegalStateException) {
                        observedFailure = failure
                    }
                }
            }
            observedFailure.shouldNotBeNull().message shouldBeEqualTo "projection target unavailable"
            targetService.getHistory(order.id) shouldHaveSize 1
        } finally {
            failedProjector.close()
        }

        val retryProjector = projector(topic, groupId, targetJavers, target)
        try {
            val retry = retryProjector.replayUntilIdle(maxIdlePolls = 3)
            retry.skippedSnapshots shouldBeEqualTo 1
            retry.projectedSnapshots shouldBeEqualTo 1
            targetService.getHistory(order.id).map { it.type } shouldBeEqualTo
                listOf(SnapshotType.UPDATE, SnapshotType.INITIAL)
        } finally {
            retryProjector.close()
        }

        val committedProjector = projector(topic, groupId, targetJavers, target)
        try {
            committedProjector.replayUntilIdle(maxIdlePolls = 3).polledRecords shouldBeEqualTo 0
        } finally {
            committedProjector.close()
            target.close()
        }
    }

    @Test
    fun `close attempts every owned resource once and suppresses later failures`() {
        val javers = JaversBuilder.javers().build()
        val service = OrderAuditService(javers)
        val consumer = MockConsumer<String, String>("earliest")
        val repository = LettuceCdoSnapshotRepository(repositoryName("close"), lettuceClient)
        val projector = KafkaCdoSnapshotProjector(
            consumer = consumer,
            jsonConverter = javers.jsonConverter,
            projectionRepository = repository,
            options = KafkaCdoSnapshotProjectionOptions(topic = uniqueName("close")),
        )
        val closed = mutableListOf<String>()
        val first = AutoCloseable {
            closed += "first"
            error("first close failure")
        }
        val second = AutoCloseable {
            closed += "second"
            error("second close failure")
        }
        val pipeline = KafkaRedisOrderAuditPipeline(service, service, projector, listOf(first, second))

        val failure = assertFailsWith<IllegalStateException> {
            pipeline.close()
        }

        failure.message shouldBeEqualTo "first close failure"
        failure.suppressed.single().message shouldBeEqualTo "second close failure"
        closed shouldBeEqualTo listOf("first", "second")
        pipeline.close()
        closed shouldBeEqualTo listOf("first", "second")
        consumer.close()
        repository.close()
    }

    private fun pipeline(
        topic: String,
        repositoryName: String,
        groupId: String,
    ): KafkaRedisOrderAuditPipeline {
        ensureTopic(topic)
        return KafkaRedisOrderAuditFactory.create(
            repositoryName = repositoryName,
            topic = topic,
            producerConfigs = producerConfigs(),
            consumerConfigs = consumerConfigs(groupId),
            redisClient = lettuceClient,
            pollTimeout = Duration.ofMillis(250),
        )
    }

    private fun ensureTopic(topic: String) {
        Admin.create(
            mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers),
        ).use { admin ->
            if (topic !in admin.listTopics().names().get()) {
                admin.createTopics(listOf(NewTopic(topic, 1, 1))).all().get()
            }
        }
    }

    private fun producerConfigs(): Map<String, Any?> =
        KafkaServer.Launcher.getProducerProperties() + mapOf(
            ProducerConfig.CLIENT_ID_CONFIG to uniqueName("producer"),
        )

    private fun consumerConfigs(groupId: String): Map<String, Any?> =
        KafkaServer.Launcher.getConsumerProperties() + mapOf(
            ConsumerConfig.CLIENT_ID_CONFIG to uniqueName("consumer"),
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        )

    private fun projector(
        topic: String,
        groupId: String,
        targetJavers: org.javers.core.Javers,
        target: CdoSnapshotRepository,
    ): KafkaCdoSnapshotProjector =
        KafkaCdoSnapshotProjector(
            consumer = KafkaConsumer(
                consumerConfigs(groupId),
                StringDeserializer(),
                StringDeserializer(),
            ),
            jsonConverter = targetJavers.jsonConverter,
            projectionRepository = target,
            options = KafkaCdoSnapshotProjectionOptions(
                topic = topic,
                pollTimeout = Duration.ofMillis(250),
                closeConsumerOnClose = true,
            ),
        )

    private fun sampleOrder(id: String): Order =
        Order(
            id = id,
            customerId = "customer-$id",
            status = OrderStatus.PLACED,
            totalAmount = BigDecimal("19.99"),
        )

    private fun repositoryName(scope: String): String = "$REPOSITORY_PREFIX:$scope:${UUID.randomUUID()}"

    private fun uniqueName(scope: String): String = "workshop-$scope-${UUID.randomUUID()}"

    private fun encodedSnapshot(orderId: String): String {
        val javers = JaversBuilder.javers().build()
        val snapshot = javers.commit("fixture", sampleOrder(orderId)).snapshots.single()
        return JaversCodecs.String.encode(javers.jsonConverter.toJsonElement(snapshot) as JsonObject)
    }

    private fun partitionInfo(topic: String, partition: Int): PartitionInfo =
        PartitionInfo(topic, partition, Node.noNode(), emptyArray(), emptyArray())

    private class FailOnSecondProjectionRepository(
        private val delegate: CdoSnapshotRepository,
    ): CdoSnapshotRepository by delegate {
        private val projections = AtomicInteger()

        override fun projectSnapshot(snapshot: CdoSnapshot) {
            if (projections.incrementAndGet() == 2) {
                error("projection target unavailable")
            }
            delegate.projectSnapshot(snapshot)
        }
    }

    private fun deleteProjectionKeys() {
        redisson.keys.deleteByPattern("javers:{$REPOSITORY_PREFIX:*}:*")
    }

    private companion object {
        private const val REPOSITORY_PREFIX = "workshop:kafka-order-audit"

        val kafka = KafkaServer.Launcher.kafka
        val lettuceClient = RedisServer.Launcher.LettuceLib.getRedisClient()
        val redisson = RedisServer.Launcher.RedissonLib.getRedisson()
    }
}
