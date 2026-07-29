package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanningOutboxRepositoryTest {

    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = PlanningOutboxRepository()
    private val now = Instant.parse("2026-07-18T00:00:00Z")

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = requireNotNull(postgres.username),
            password = requireNotNull(postgres.password),
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

        val workers = 4
        val barrier = CyclicBarrier(workers)
        val claimed = AtomicInteger()
        val workerSequence = AtomicInteger()

        MultithreadingTester()
            .workers(workers)
            .rounds(1)
            .add {
                val workerIndex = workerSequence.incrementAndGet()
                barrier.await(5, TimeUnit.SECONDS)
                transaction {
                    claimed.addAndGet(
                        repository.claimNextBatch(
                            workerId = "worker-$workerIndex",
                            batchSize = 1,
                            now = now,
                            leaseDuration = Duration.ofSeconds(30),
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
            repository.claimNextBatch("worker-a", 1, now, Duration.ofSeconds(30)).single()
        }
        first.claimedBy shouldBeEqualTo "worker-a"

        val reclaimed = transaction {
            repository.claimNextBatch("worker-b", 1, now.plusSeconds(31), Duration.ofSeconds(30)).single()
        }
        reclaimed.claimedBy shouldBeEqualTo "worker-b"
    }

    @Test
    fun `maximum retries move request to dead letter`() {
        transaction {
            repository.save(outboxRecord())
            repository.claimNextBatch("worker-a", 1, now, Duration.ofSeconds(30)).single()

            repository.markFailure(
                planningRequestId = REQUEST_ID,
                workerId = "worker-a",
                now = now,
                retryDelay = Duration.ofSeconds(5),
                maxRetries = 1,
                errorCode = "PROVIDER_UNAVAILABLE",
                errorSummary = "provider token=must-not-leak",
            ) shouldBeEqualTo PlanningOutboxStatus.DEAD_LETTER

            val stored = repository.findByRequestId(REQUEST_ID)!!
            stored.status shouldBeEqualTo PlanningOutboxStatus.DEAD_LETTER
            stored.retryCount shouldBeEqualTo 1
            stored.lastErrorSummary shouldBeEqualTo "provider [redacted]"
        }
    }

    private fun outboxRecord() = PlanningOutboxRecord(
        planningRequestId = REQUEST_ID,
        payload = "{\"datasetId\":\"dataset-42\"}",
        nextAttemptAt = now,
    )

    companion object {
        private val REQUEST_ID = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b")
    }
}
