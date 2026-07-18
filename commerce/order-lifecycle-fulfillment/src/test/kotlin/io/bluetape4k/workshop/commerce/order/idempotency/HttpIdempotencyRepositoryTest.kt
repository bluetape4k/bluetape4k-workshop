package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.order.persistence.HttpIdempotencyTable
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
internal class HttpIdempotencyRepositoryTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = HttpIdempotencyRepository()
    private val now = Instant.parse("2026-07-18T00:00:00Z")
    private val scope = IdempotencyScope("tenant-a", "submit-order", IdempotencyFingerprint.key("key-a"))
    private val fingerprint = IdempotencyFingerprint.request("sku-a=1")

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = requireNotNull(postgres.username),
            password = requireNotNull(postgres.password)
        )
    }

    @BeforeEach
    fun createSchema() =
        transaction {
            SchemaUtils.drop(HttpIdempotencyTable)
            SchemaUtils.create(HttpIdempotencyTable)
        }

    @AfterEach
    fun dropSchema() = transaction { SchemaUtils.drop(HttpIdempotencyTable) }

    @Test
    fun `same key and payload replays terminal response`() {
        val owner = UUID.randomUUID()
        val acquired = transaction { acquire(owner) } as AcquireResult.Acquired
        transaction {
            repository.finalize(
                acquired.record.id,
                owner,
                201,
                "{\"orderId\":\"o-1\"}",
                false
            )
        } shouldBeEqualTo
            true

        transaction { acquire(UUID.randomUUID()) } shouldBeEqualTo
            AcquireResult.Replay(201, "{\"orderId\":\"o-1\"}", failed = false)
    }

    @Test
    fun `same key and different payload returns deterministic conflict`() {
        transaction { acquire(UUID.randomUUID()) }

        val result =
            transaction {
                repository.acquire(
                    scope,
                    IdempotencyFingerprint.request("sku-a=2"),
                    UUID.randomUUID(),
                    now,
                    Duration.ofSeconds(30),
                    Duration.ofDays(1)
                )
            }
        result shouldBeEqualTo AcquireResult.FingerprintConflict
    }

    @Test
    fun `expired owner is replaced and stale finalize is rejected`() {
        val staleOwner = UUID.randomUUID()
        val first = transaction { acquire(staleOwner) } as AcquireResult.Acquired
        val replacement = UUID.randomUUID()
        val reclaimed =
            transaction {
                repository.acquire(
                    scope,
                    fingerprint,
                    replacement,
                    now.plusSeconds(31),
                    Duration.ofSeconds(30),
                    Duration.ofDays(1)
                )
            } as AcquireResult.Acquired

        reclaimed.record.ownerToken shouldBeEqualTo replacement
        transaction { repository.finalize(first.record.id, staleOwner, 201, "stale", false) } shouldBeEqualTo false
        transaction { repository.finalize(first.record.id, replacement, 201, "fresh", false) } shouldBeEqualTo true
    }

    @Test
    fun `concurrent requests acquire one owner`() {
        val workers = 4
        val barrier = CyclicBarrier(workers)
        val acquired = AtomicInteger()

        MultithreadingTester()
            .workers(workers)
            .rounds(1)
            .add {
                barrier.await(5, TimeUnit.SECONDS)
                val result = transaction { acquire(UUID.randomUUID()) }
                if (result is AcquireResult.Acquired) acquired.incrementAndGet()
            }.run()

        acquired.get() shouldBeEqualTo 1
    }

    private fun acquire(owner: UUID): AcquireResult =
        repository.acquire(
            scope = scope,
            fingerprint = fingerprint,
            ownerToken = owner,
            now = now,
            lease = Duration.ofSeconds(30),
            retention = Duration.ofDays(1)
        )
}
