package io.bluetape4k.workshop.exposed.javers.persistence

import com.google.gson.JsonObject
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.javers.codecs.JaversCodec
import io.bluetape4k.javers.codecs.JaversCodecs
import io.bluetape4k.testcontainers.storage.RedisServer
import org.javers.core.JaversBuilder
import org.javers.core.metamodel.`object`.SnapshotType
import org.javers.repository.jql.JqlQuery
import org.javers.repository.jql.QueryBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class BoundedRedissonCdoSnapshotRepositoryTest {

    @AfterEach
    fun tearDown() {
        redis.keys.getKeysByPattern("javers:$REPOSITORY_PREFIX:*")
            .forEach { redis.keys.delete(it) }
    }

    @Test
    fun `unsupported query parameters delegate without changing JaVers semantics`() {
        val codec = CountingCodec()
        val repositoryName = "$REPOSITORY_PREFIX:${System.nanoTime()}"
        val repository = BoundedRedissonCdoSnapshotRepository(repositoryName, redis, codec)
        val javers = JaversBuilder.javers()
            .registerJaversRepository(repository)
            .build()
        val initial = sampleOrder()
        javers.commit("alice", initial, mapOf("tenant" to "blue"))
        val update = javers.commit("bob", initial.copy(status = OrderStatus.PAID))
        javers.commit("carol", initial.copy(totalAmount = BigDecimal("29.99")))

        val fallbackQueries = listOf<Pair<String, () -> JqlQuery>>(
            "skip" to { instanceQuery().skip(1).limit(1).build() },
            "aggregate" to { instanceQuery().withChildValueObjects().limit(1).build() },
            "author" to { instanceQuery().byAuthor("alice").limit(1).build() },
            "authorLikeIgnoreCase" to { instanceQuery().byAuthorLikeIgnoreCase("ALI").limit(1).build() },
            "date" to { instanceQuery().from(LocalDate.now().minusDays(1)).limit(1).build() },
            "toDate" to { instanceQuery().to(LocalDate.now().plusDays(1)).limit(1).build() },
            "fromInstant" to { instanceQuery().fromInstant(Instant.now().minusSeconds(86_400)).limit(1).build() },
            "toInstant" to { instanceQuery().toInstant(Instant.now().plusSeconds(86_400)).limit(1).build() },
            "version" to { instanceQuery().withVersion(2L).limit(1).build() },
            "fromVersion" to { instanceQuery().fromVersion(2L).limit(1).build() },
            "toVersion" to { instanceQuery().toVersion(2L).limit(1).build() },
            "commitIds" to { instanceQuery().withCommitId(update.id).limit(1).build() },
            "toCommitId" to { instanceQuery().toCommitId(update.id).limit(1).build() },
            "changedProperties" to { instanceQuery().withChangedProperty("status").limit(1).build() },
            "snapshotType" to { instanceQuery().withSnapshotType(SnapshotType.UPDATE).limit(1).build() },
            "commitProperties" to { instanceQuery().withCommitProperty("tenant", "blue").limit(1).build() },
            "commitPropertiesLike" to {
                instanceQuery().withCommitPropertyLike("tenant", "blu").limit(1).build()
            },
        )

        fallbackQueries.forEach { (_, query) ->
            codec.resetDecodeCount()

            val snapshots = javers.findSnapshots(query())

            snapshots shouldHaveSize 1
            codec.decodeCount.get() shouldBeEqualTo 3
        }
    }

    @Test
    fun `bounded range remains newest first while snapshots are appended`() {
        val codec = CountingCodec()
        val repositoryName = "$REPOSITORY_PREFIX:concurrent:${System.nanoTime()}"
        val repository = BoundedRedissonCdoSnapshotRepository(repositoryName, redis, codec)
        val javers = JaversBuilder.javers()
            .registerJaversRepository(repository)
            .build()
        val initial = sampleOrder()
        javers.commit("writer", initial)
        val start = CountDownLatch(1)

        val writer = CompletableFuture.runAsync {
            start.await()
            (2..30).forEach { version ->
                javers.commit(
                    "writer",
                    initial.copy(totalAmount = BigDecimal("$version.00")),
                )
            }
        }
        val reader = CompletableFuture.runAsync {
            start.await()
            repeat(50) {
                val snapshots = javers.findSnapshots(instanceQuery().limit(2).build())

                (snapshots.size <= 2).shouldBeTrue()
                snapshots.zipWithNext().all { (newer, older) -> newer.version > older.version }.shouldBeTrue()
            }
        }

        start.countDown()
        CompletableFuture.allOf(writer, reader).join()

        val latest = javers.findSnapshots(instanceQuery().limit(2).build())
        latest.map { it.version } shouldBeEqualTo listOf(30L, 29L)
    }

    private fun instanceQuery(): QueryBuilder =
        QueryBuilder.byInstanceId(ORDER_ID, Order::class.java)

    private fun sampleOrder(): Order =
        Order(
            id = ORDER_ID,
            customerId = "customer-1",
            status = OrderStatus.PLACED,
            totalAmount = BigDecimal("19.99"),
        )

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
        private const val REPOSITORY_PREFIX = "workshop:bounded-history"
        private const val ORDER_ID = "order-fallback"

        val redis = RedisServer.Launcher.RedissonLib.getRedisson()
    }
}
