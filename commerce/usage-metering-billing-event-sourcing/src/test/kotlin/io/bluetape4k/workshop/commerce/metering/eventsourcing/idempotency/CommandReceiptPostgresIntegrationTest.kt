@file:Suppress("MaxLineLength") // Exact timestamps and receipt scenarios stay readable at each transaction boundary.

package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.*


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandReceiptPostgresIntegrationTest {

    companion object: KLogging()

    private val fixture = EventStoreDatabaseFixture()
    private val repository = CommandReceiptRepository()
    private val eventStore = EventStoreRepository()
    private val service = CommandReceiptService(repository, 30.seconds(), 24.hours())
    private val scope = CommandScope("tenant-a", "meter-register", CommandFingerprint.key("stable-key"))
    private val fingerprint = CommandFingerprint.request("meter-register", mapOf("code" to "api_calls"))

    @Test
    fun `same command replays its exact terminal response`() {
        fixture.reset()
        val now = Instant.parse("2026-07-01T00:00:00Z")

        val owned = fixture.executor.transaction {
            service.acquire(scope, fingerprint, now)
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        fixture.executor.transaction {
            service.succeed(owned, 201, "{\"code\":\"api_calls\"}", now)
        }.shouldBeTrue()

        val replay = fixture.executor.transaction {
            service.acquire(scope, fingerprint, now)
        }

        log.debug { "replay: $replay" }
        replay shouldBeEqualTo CommandAcquireResult.Replay(201, "{\"code\":\"api_calls\"}")
    }

    @Test
    fun `same key with different fingerprint conflicts`() {
        fixture.reset()
        val now = Instant.parse("2026-07-01T00:00:00Z")

        fixture.executor.transaction {
            service.acquire(scope, fingerprint, now)
        }

        val result = fixture.executor.transaction {
            service.acquire(
                scope,
                CommandFingerprint.request("meter-register", mapOf("code" to "other")),
                now
            )
        }

        result shouldBeEqualTo CommandAcquireResult.Conflict
    }

    @Test
    fun `expired owner is fenced after takeover`() {
        fixture.reset()
        val acquiredAt = Instant.parse("2026-07-01T00:00:00Z")
        val first = fixture.executor.transaction {
            service.acquire(
                scope,
                fingerprint,
                acquiredAt
            )
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        val second = fixture.executor.transaction {
            service.acquire(scope, fingerprint, acquiredAt + 31.seconds())
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        fixture.executor.transaction {
            service.succeed(first, 201, "{}", acquiredAt + 31.seconds())
        }.shouldBeFalse()

        fixture.executor.transaction {
            service.succeed(second, 201, "{}", acquiredAt + 31.seconds())
        }.shouldBeTrue()
    }

    @Test
    fun `stale owner is rejected before event append`() {
        fixture.reset()
        val acquiredAt = Instant.parse("2026-07-01T00:00:00Z")
        val first = fixture.executor.transaction {
            service.acquire(
                scope,
                fingerprint,
                acquiredAt
            )
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        fixture.executor.transaction {
            service.acquire(scope, fingerprint, acquiredAt + 31.seconds())
        }

        assertFailsWith<CommandOwnerLostException> {
            fixture.executor.transaction {
                service.requireOwnership(first, acquiredAt + 31.seconds())
            }
        }
    }

    @Test
    fun `event append and terminal response roll back together`() {
        fixture.reset()
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val owned = fixture.executor.transaction {
            service.acquire(scope, fingerprint, now)
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        val stream = StreamKey("tenant-a", "Usage", "atomic")

        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                service.requireOwnership(owned, now)
                eventStore.append(stream, 0, listOf(usageEvent(now)))
                check(service.succeed(owned, 201, "{}", now))
                error("force_rollback")
            }
        }

        fixture.executor.transaction { eventStore.load(stream) }.shouldBeEmpty()
        fixture.executor.transaction {
            service.acquire(scope, fingerprint, now + 1.seconds())
        }.shouldBeInstanceOf<CommandAcquireResult.InProgress>()
    }

    private fun usageEvent(now: Instant): NewEvent =
        NewEvent(
            eventId = UUID.randomUUID(),
            event = UsageAccepted(
                sourceSystem = "gateway",
                sourceEventId = "atomic",
                meterCode = "api_calls",
                quantity = BigDecimal.ONE,
                occurredAt = now
            ),
            payload = """{"sourceEventId":"atomic","quantity":1}""",
            metadata = """{"actor":"tenant-a"}""",
            occurredAt = now,
        )
}
