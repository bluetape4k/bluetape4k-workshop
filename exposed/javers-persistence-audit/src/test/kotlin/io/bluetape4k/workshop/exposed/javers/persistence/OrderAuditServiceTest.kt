package io.bluetape4k.workshop.exposed.javers.persistence

import com.google.gson.JsonObject
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javers.codecs.JaversCodec
import io.bluetape4k.javers.codecs.JaversCodecs
import io.bluetape4k.javers.persistence.redis.repository.RedissonCdoSnapshotRepository
import io.bluetape4k.testcontainers.storage.RedisServer
import org.javers.core.JaversBuilder
import org.javers.core.metamodel.`object`.SnapshotType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.redisson.client.codec.LongCodec
import org.redisson.client.codec.StringCodec
import org.redisson.codec.CompositeCodec
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

class OrderAuditServiceTest {

    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        deleteOrderAuditKeys()
        database = Database.connect(
            url = "jdbc:h2:mem:order-audit-${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
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
        deleteOrderAuditKeys()
    }

    @Test
    fun `commits survive service rebuild through Redis repository`() {
        val repositoryName = repositoryName("rebuild")
        val service = redisService(repositoryName)
        val order = sampleOrder("order-100")
        service.place("alice", order)
        service.markPaid("alice", order.id)

        val rebuilt = redisService(repositoryName)

        val history = rebuilt.getHistory(order.id)
        history shouldHaveSize 2
        history.first().type shouldBeEqualTo SnapshotType.UPDATE
        history.first().version shouldBeEqualTo 2L
        history.last().type shouldBeEqualTo SnapshotType.INITIAL
        history.last().version shouldBeEqualTo 1L
        rebuilt.getLatestSnapshot(order.id)
            .shouldNotBeNull()
            .getPropertyValue("status") shouldBeEqualTo OrderStatus.PAID
    }

    @Test
    fun `diff query reports amount changes without writing audit snapshots`() {
        val service = redisService(repositoryName("diff"))
        val original = sampleOrder("order-200")
        val updated = original.copy(totalAmount = BigDecimal("25.50"))

        val diff = service.diff(original, updated)

        diff.hasChanges().shouldBeTrue()
        service.getHistory(original.id) shouldHaveSize 0
    }

    @Test
    fun `delete records terminal snapshot and removes current row`() {
        val service = redisService(repositoryName("delete"))
        val order = sampleOrder("order-300")
        service.place("carol", order)

        service.delete("carol", order.id)

        val history = service.getHistory(order.id)
        history shouldHaveSize 2
        history.first().type shouldBeEqualTo SnapshotType.TERMINAL
        history.first().version shouldBeEqualTo 2L
        service.findCurrent(order.id) shouldBeEqualTo null
    }

    @Test
    fun `bounded history decodes only the requested newest Redis snapshots`() {
        val codec = CountingCodec()
        val service = RedisOrderAuditFactory.create(repositoryName("bounded"), redis, codec)
        val order = sampleOrder("order-350")
        service.place("alice", order)
        service.markPaid("bob", order.id)
        service.delete("carol", order.id)

        codec.resetDecodeCount()
        val latest = service.getHistory(order.id, 1)

        latest shouldHaveSize 1
        latest.single().type shouldBeEqualTo SnapshotType.TERMINAL
        latest.single().version shouldBeEqualTo 3L
        codec.decodeCount.get() shouldBeEqualTo 1

        codec.resetDecodeCount()
        val recent = service.getHistory(order.id, 2)

        recent.map { it.type } shouldBeEqualTo listOf(SnapshotType.TERMINAL, SnapshotType.UPDATE)
        recent.map { it.version } shouldBeEqualTo listOf(3L, 2L)
        codec.decodeCount.get() shouldBeEqualTo 2
    }

    @Test
    fun `history validates bounds preserves empty semantics and JVM overloads`() {
        val codec = CountingCodec()
        val service = RedisOrderAuditFactory.create(repositoryName("contract"), redis, codec)
        val order = sampleOrder("order-360")
        service.place("alice", order)

        codec.resetDecodeCount()
        service.getHistory(order.id, 100) shouldHaveSize 1
        codec.decodeCount.get() shouldBeEqualTo 1
        service.getHistory("unknown-order") shouldHaveSize 0

        codec.resetDecodeCount()
        listOf(0, -1, 101).forEach { invalidLimit ->
            assertFailsWith<IllegalArgumentException> {
                service.getHistory(order.id, invalidLimit)
            }
        }
        codec.decodeCount.get() shouldBeEqualTo 0

        val oneArgument = service.javaClass.getMethod("getHistory", String::class.java)
        val twoArguments = service.javaClass.getMethod(
            "getHistory",
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        (oneArgument.invoke(service, order.id) as List<*>) shouldHaveSize 1
        (twoArguments.invoke(service, order.id, 1) as List<*>) shouldHaveSize 1
    }

    @Test
    fun `audit sink failure propagates instead of accepting unaudited write`() {
        val repository = RedissonCdoSnapshotRepository(repositoryName("failure"), redis, FailingCodec)
        val javers = JaversBuilder.javers()
            .registerJaversRepository(repository)
            .build()
        val service = OrderAuditService(javers)
        val order = sampleOrder("order-400")

        assertFailsWith<RuntimeException> {
            service.place("dave", order)
        }
        service.findCurrent(order.id) shouldBeEqualTo null
    }

    @Test
    fun `missing head metadata remains an empty initial audit state`() {
        val service = redisService(repositoryName("missing-head"))

        service.getHistory("unknown-order") shouldHaveSize 0
        service.getLatestSnapshot("unknown-order") shouldBeEqualTo null
    }

    @Test
    fun `malformed Redisson commit id fails closed without exposing identifiers`() {
        val repositoryName = repositoryName("corrupt-head")
        val service = redisService(repositoryName)
        val order = sampleOrder("order-sensitive-894")
        service.place("alice", order)
        val rawCommitId = "customer-secret-order-894"
        val sequenceKey = "javers:$repositoryName:sequence"
        redis.getMap<String, Long>(
            sequenceKey,
            CompositeCodec(StringCodec.INSTANCE, LongCodec.INSTANCE),
        ).fastPut(rawCommitId, Long.MAX_VALUE)

        val failure = assertFailsWith<IllegalStateException> {
            RedisOrderAuditFactory.create(repositoryName, redis)
        }
        val message = failure.message.orEmpty()
        message.contains("type=commitId").shouldBeTrue()
        message.contains(Regex("fingerprint=[0-9a-f]{16}")).shouldBeTrue()
        message.contains("length=${rawCommitId.length}").shouldBeTrue()
        message.contains(rawCommitId) shouldBeEqualTo false
        message.contains(repositoryName) shouldBeEqualTo false
        message.contains(sequenceKey) shouldBeEqualTo false
        message.contains(order.id) shouldBeEqualTo false
    }

    @Test
    fun `snapshots without Redisson head metadata are not treated as an initial state`() {
        val repositoryName = repositoryName("missing-head-with-snapshot")
        redisService(repositoryName).place("alice", sampleOrder("order-existing-894"))
        redis.keys.delete("javers:$repositoryName:sequence")

        val failure = assertFailsWith<IllegalStateException> {
            RedisOrderAuditFactory.create(repositoryName, redis)
        }

        failure.message shouldBeEqualTo
            "Corrupted Redis audit metadata. persisted Order snapshots exist without head metadata."
        failure.message.orEmpty().contains(repositoryName) shouldBeEqualTo false
        failure.message.orEmpty().contains("order-existing-894") shouldBeEqualTo false
    }

    private fun redisService(repositoryName: String): OrderAuditService =
        RedisOrderAuditFactory.create(repositoryName, redis)

    private fun repositoryName(scope: String): String =
        "$REPOSITORY_PREFIX:$scope:${System.nanoTime()}"

    private fun deleteOrderAuditKeys() {
        redis.keys.getKeysByPattern("javers:$REPOSITORY_PREFIX:*")
            .forEach { redis.keys.delete(it) }
    }

    private fun sampleOrder(id: String): Order =
        Order(
            id = id,
            customerId = "customer-$id",
            status = OrderStatus.PLACED,
            totalAmount = BigDecimal("19.99"),
        )

    private object FailingCodec: JaversCodec<ByteArray> {
        override fun encode(jsonElement: JsonObject): ByteArray =
            throw RuntimeException("audit sink unavailable")

        override fun decode(encodedData: ByteArray): JsonObject? =
            JaversCodecs.Fory.decode(encodedData)
    }

    private class CountingCodec(
        private val delegate: JaversCodec<ByteArray> = JaversCodecs.LZ4Fory,
    ): JaversCodec<ByteArray> {
        val decodeCount = AtomicInteger()

        override fun encode(jsonElement: JsonObject): ByteArray = delegate.encode(jsonElement)

        override fun decode(encodedData: ByteArray): JsonObject? {
            decodeCount.incrementAndGet()
            return delegate.decode(encodedData)
        }

        fun resetDecodeCount() {
            decodeCount.set(0)
        }
    }

    private companion object {
        private const val REPOSITORY_PREFIX = "workshop:order-audit"

        val redis = RedisServer.Launcher.RedissonLib.getRedisson()
    }
}
