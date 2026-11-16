package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.logging.KLogging
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
import java.util.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class HttpIdempotencyRepositoryTest {

    companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

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
    fun dropSchema() {
        transaction { SchemaUtils.drop(HttpIdempotencyTable) }
    }

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
            ).shouldBeTrue()
        }

        transaction {
            acquire(UUID.randomUUID())
        } shouldBeEqualTo AcquireResult.Replay(201, "{\"orderId\":\"o-1\"}", failed = false)
    }

    @Test
    fun `same key and different payload returns deterministic conflict`() {
        transaction { acquire(UUID.randomUUID()) }

        val result = transaction {
            repository.acquire(
                scope = scope,
                fingerprint = IdempotencyFingerprint.request("sku-a=2"),
                ownerToken = UUID.randomUUID(),
                now = now,
                lease = 30.seconds(),
                retention = 1.days()
            )
        }
        result shouldBeEqualTo AcquireResult.FingerprintConflict
    }

    @Test
    fun `expired owner is replaced and stale finalize is rejected`() {
        val staleOwner = UUID.randomUUID()
        val first = transaction { acquire(staleOwner) } as AcquireResult.Acquired
        val replacement = UUID.randomUUID()

        val reclaimed = transaction {
            repository.acquire(
                scope = scope,
                fingerprint = fingerprint,
                ownerToken = replacement,
                now = now + 31.seconds(),
                lease = 30.seconds(),
                retention = 1.days()
            ).shouldBeInstanceOf<AcquireResult.Acquired>()
        }
        reclaimed.record.ownerToken shouldBeEqualTo replacement

        transaction {
            repository.finalize(first.record.id, staleOwner, 201, "stale", false)
        }.shouldBeFalse()

        transaction {
            repository.finalize(first.record.id, replacement, 201, "fresh", false)
        }.shouldBeTrue()
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
                barrier.await(5.seconds)
                val result = transaction { acquire(UUID.randomUUID()) }
                if (result is AcquireResult.Acquired) acquired.incrementAndGet()
            }.run()

        acquired.get() shouldBeEqualTo 1
    }

    @Test
    fun `cleanup deletes only expired terminal records and preserves in progress owners`() {
        val expiredSucceeded = IdempotencyScope(
            "tenant-a",
            "submit-order",
            IdempotencyFingerprint.key("expired-success")
        )
        val expiredFailed = IdempotencyScope(
            "tenant-a",
            "submit-order",
            IdempotencyFingerprint.key("expired-failure")
        )
        val expiredInProgress = IdempotencyScope(
            "tenant-a",
            "submit-order",
            IdempotencyFingerprint.key("expired-owner")
        )
        val retainedTerminal = IdempotencyScope(
            "tenant-a",
            "submit-order",
            IdempotencyFingerprint.key("retained-success")
        )

        transaction {
            acquireAndFinalize(expiredSucceeded, retention = 1.seconds(), failed = false)
            acquireAndFinalize(expiredFailed, retention = 1.seconds(), failed = true)
            acquireScope(expiredInProgress, retention = 1.seconds())
            acquireAndFinalize(retainedTerminal, retention = 1.days(), failed = false)
        }

        transaction {
            repository.deleteExpiredTerminal(now + 2.seconds(), limit = 10)
        } shouldBeEqualTo 2

        transaction {
            repository.findByScope(expiredSucceeded).shouldBeNull()
            repository.findByScope(expiredFailed).shouldBeNull()
            repository.findByScope(expiredInProgress).shouldNotBeNull()
            repository.findByScope(retainedTerminal).shouldNotBeNull()
        }
    }

    private fun acquire(owner: UUID): AcquireResult =
        repository.acquire(
            scope = scope,
            fingerprint = fingerprint,
            ownerToken = owner,
            now = now,
            lease = 30.seconds(),
            retention = 1.days()
        )

    private fun acquireScope(
        targetScope: IdempotencyScope,
        retention: Duration,
    ): AcquireResult =
        repository.acquire(
            scope = targetScope,
            fingerprint = fingerprint,
            ownerToken = UUID.randomUUID(),
            now = now,
            lease = 30.seconds(),
            retention = retention
        )

    private fun acquireAndFinalize(
        targetScope: IdempotencyScope,
        retention: Duration,
        failed: Boolean,
    ) {
        val acquired = acquireScope(targetScope, retention).shouldBeInstanceOf<AcquireResult.Acquired>()
        
        repository.finalize(
            id = acquired.record.id,
            ownerToken = acquired.record.ownerToken,
            status = if (failed) 503 else 201,
            body = if (failed) "{\"code\":\"FAILED\"}" else "{\"orderId\":\"o-1\"}",
            failed = failed
        ).shouldBeTrue()
    }
}
