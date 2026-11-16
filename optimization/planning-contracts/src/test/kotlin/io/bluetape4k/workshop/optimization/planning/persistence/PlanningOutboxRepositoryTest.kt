package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.utils.Runtimex
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanningOutboxRepositoryTest {

    companion object: KLogging() {
        private val REQUEST_ID = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b")
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }


    private val repository = PlanningOutboxRepository()
    private val now = Instant.parse("2026-07-18T00:00:00Z")

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username.shouldNotBeNull(),
            password = postgres.password.shouldNotBeNull(),
        )
    }

    @BeforeEach
    fun createSchema() {
        transaction {
            SchemaUtils.drop(PlanningOutboxTable)
            SchemaUtils.create(PlanningOutboxTable)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction {
            SchemaUtils.drop(PlanningOutboxTable)
        }
    }

    @Test
    fun `concurrent workers claim one request only once`() {
        transaction { repository.save(outboxRecord()) }

        val workers = Runtimex.availableProcessors
        val barrier = CyclicBarrier(workers)
        val claimed = AtomicInteger()
        val workerSequence = AtomicInteger()

        MultithreadingTester()
            .workers(workers)
            .rounds(1)
            .add {
                val workerIndex = workerSequence.incrementAndGet()
                barrier.await(5.seconds)
                transaction {
                    claimed.addAndGet(
                        repository.claimNextBatch(
                            workerId = "worker-$workerIndex",
                            batchSize = 1,
                            now = now,
                            leaseDuration = 30.seconds(),
                        ).size,
                    )
                }
            }
            .run()

        claimed.get() shouldBeEqualTo 1
    }

    @Test
    fun `expired lease can be reclaimed by another worker`() {
        transaction { repository.save(outboxRecord()) }

        val first = transaction {
            repository.claimNextBatch("worker-a", 1, now, 30.seconds()).single()
        }
        log.debug { "first=$first" }
        first.claimedBy shouldBeEqualTo "worker-a"

        val reclaimed = transaction {
            repository.claimNextBatch("worker-b", 1, now + 31.seconds(), 30.seconds()).single()
        }
        log.debug { "reclaimed=$reclaimed" }
        reclaimed.claimedBy shouldBeEqualTo "worker-b"
    }

    @Test
    fun `maximum retries move request to dead letter`() {
        transaction {
            repository.save(outboxRecord())
            val loaded = repository.claimNextBatch("worker-a", 1, now, 30.seconds()).single()
            log.debug { "loaded=$loaded" }

            repository.markFailure(
                planningRequestId = REQUEST_ID,
                workerId = "worker-a",
                now = now,
                retryDelay = 5.seconds(),
                maxRetries = 1,
                errorCode = "PROVIDER_UNAVAILABLE",
                errorSummary = "provider token=must-not-leak",
            ) shouldBeEqualTo PlanningOutboxStatus.DEAD_LETTER

            val stored = repository.findByRequestId(REQUEST_ID).shouldNotBeNull()

            log.debug { "stored=$stored" }
            stored.status shouldBeEqualTo PlanningOutboxStatus.DEAD_LETTER
            stored.retryCount shouldBeEqualTo 1
            stored.lastErrorSummary shouldBeEqualTo "provider [redacted]"
        }
    }

    private fun outboxRecord(): PlanningOutboxRecord =
        PlanningOutboxRecord(
            planningRequestId = REQUEST_ID,
            payload = "{\"datasetId\":\"dataset-42\"}",
            nextAttemptAt = now,
        )
}
