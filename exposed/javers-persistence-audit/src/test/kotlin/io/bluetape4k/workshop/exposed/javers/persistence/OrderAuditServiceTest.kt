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
import java.math.BigDecimal

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
        history.first().type shouldBeEqualTo SnapshotType.INITIAL
        history.last().type shouldBeEqualTo SnapshotType.UPDATE
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
        history.last().type shouldBeEqualTo SnapshotType.TERMINAL
        service.findCurrent(order.id) shouldBeEqualTo null
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

    private companion object {
        private const val REPOSITORY_PREFIX = "workshop:order-audit"

        val redis = RedisServer.Launcher.RedissonLib.getRedisson()
    }
}
