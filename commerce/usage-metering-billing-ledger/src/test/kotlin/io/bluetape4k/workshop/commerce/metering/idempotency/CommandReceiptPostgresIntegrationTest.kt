@file:Suppress("MaxLineLength") // Full receipt transitions remain visible as single assertions.

package io.bluetape4k.workshop.commerce.metering.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeteringDatabaseFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandReceiptPostgresIntegrationTest {
    private val fixture = MeteringDatabaseFixture()
    private val repository = CommandReceiptRepository()
    private val clock = MutableClock(Instant.parse("2026-07-22T00:00:00Z"))
    private val service = CommandReceiptService(repository, MeteringProperties(), clock)

    @AfterAll
    fun close(): Unit = fixture.close()

    @Test
    fun `same request replays while a different fingerprint conflicts`() {
        fixture.resetAndSeed()
        val scope = scope("replay")
        val fingerprint = CommandFingerprint.request("usage", mapOf("quantity" to "1"))
        val acquired = transaction { service.acquire(scope, fingerprint) }.shouldBeInstanceOf<CommandAcquireResult.Acquired>()

        transaction { service.succeed(acquired.receipt.id, acquired.receipt.ownerToken, 201, "accepted") }.shouldBeTrue()
        transaction { service.acquire(scope, fingerprint) } shouldBeEqualTo
            CommandAcquireResult.Replay(201, "accepted", failed = false)
        transaction {
            service.acquire(scope, CommandFingerprint.request("usage", mapOf("quantity" to "2")))
        } shouldBeEqualTo CommandAcquireResult.Conflict
    }

    @Test
    fun `expired owner can be replaced and stale owner cannot complete`() {
        fixture.resetAndSeed()
        val scope = scope("takeover")
        val fingerprint = CommandFingerprint.request("usage", mapOf("quantity" to "1"))
        val first = transaction { service.acquire(scope, fingerprint) }.shouldBeInstanceOf<CommandAcquireResult.Acquired>()

        transaction { service.acquire(scope, fingerprint) }.shouldBeInstanceOf<CommandAcquireResult.InProgress>()
        clock.advance(Duration.ofSeconds(31))
        val second = transaction { service.acquire(scope, fingerprint) }.shouldBeInstanceOf<CommandAcquireResult.Acquired>()

        second.takeover.shouldBeTrue()
        transaction { service.succeed(first.receipt.id, first.receipt.ownerToken, 201, "stale") } shouldBeEqualTo false
        transaction { service.succeed(second.receipt.id, second.receipt.ownerToken, 201, "winner") }.shouldBeTrue()
    }

    @Test
    fun `terminal response is bounded and cleanup never removes in progress receipts`() {
        fixture.resetAndSeed()
        val terminal = transaction { service.acquire(scope("terminal"), CommandFingerprint.key("terminal")) }
            .shouldBeInstanceOf<CommandAcquireResult.Acquired>()
        val live = transaction { service.acquire(scope("live"), CommandFingerprint.key("live")) }
            .shouldBeInstanceOf<CommandAcquireResult.Acquired>()

        assertFailsWith<IllegalArgumentException> {
            transaction {
                service.succeed(
                    terminal.receipt.id,
                    terminal.receipt.ownerToken,
                    200,
                    "x".repeat(MeteringProperties.MAX_TERMINAL_RESPONSE_BYTES + 1),
                )
            }
        }
        transaction { service.succeed(terminal.receipt.id, terminal.receipt.ownerToken, 200, "ok") }.shouldBeTrue()
        clock.advance(Duration.ofHours(25))

        transaction { service.cleanupExpiredTerminal() } shouldBeEqualTo 1
        transaction { repository.find(scope("terminal")) } shouldBeEqualTo null
        (transaction { repository.find(scope("live")) } != null).shouldBeTrue()
        live.receipt.status.name shouldBeEqualTo "IN_PROGRESS"
    }

    @Test
    fun `twenty contenders elect one receipt owner`() {
        fixture.resetAndSeed()
        val scope = scope("twenty-contenders")
        val fingerprint = CommandFingerprint.key("same-request")
        Executors.newFixedThreadPool(8).use { pool ->
            val results = pool.invokeAll(
                List(20) {
                    Callable { transaction { service.acquire(scope, fingerprint) } }
                },
            ).map { it.get() }

            results.count { it is CommandAcquireResult.Acquired } shouldBeEqualTo 1
            results.count { it is CommandAcquireResult.InProgress } shouldBeEqualTo 19
        }
    }

    private fun scope(suffix: String): CommandReceiptScope =
        CommandReceiptScope("tenant-a", "usage-ingest", CommandFingerprint.key("key-$suffix").value)

    private fun <T> transaction(block: () -> T): T = fixture.executor.transaction { block() }
}

private class MutableClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
