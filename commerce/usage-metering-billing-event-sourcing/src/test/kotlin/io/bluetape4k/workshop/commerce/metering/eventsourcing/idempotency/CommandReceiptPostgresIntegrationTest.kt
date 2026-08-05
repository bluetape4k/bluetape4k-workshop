@file:Suppress("MaxLineLength") // Exact timestamps and receipt scenarios stay readable at each transaction boundary.

package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.math.BigDecimal
import java.util.UUID

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandReceiptPostgresIntegrationTest {
    private val fixture = EventStoreDatabaseFixture()
    private val repository = CommandReceiptRepository()
    private val eventStore = EventStoreRepository()
    private val service = CommandReceiptService(repository, Duration.ofSeconds(30), Duration.ofHours(24))
    private val scope = CommandScope("tenant-a", "meter-register", CommandFingerprint.key("stable-key"))
    private val fingerprint = CommandFingerprint.request("meter-register", mapOf("code" to "api_calls"))

    @Test
    fun `same command replays its exact terminal response`() {
        fixture.reset()
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val owned = fixture.executor.transaction { service.acquire(scope, fingerprint, now) } as CommandAcquireResult.Owned
        fixture.executor.transaction { service.succeed(owned, 201, "{\"code\":\"api_calls\"}", now) }.shouldBeTrue()

        val replay = fixture.executor.transaction { service.acquire(scope, fingerprint, now) }

        replay.shouldBeEqualTo(CommandAcquireResult.Replay(201, "{\"code\":\"api_calls\"}"))
    }

    @Test
    fun `same key with different fingerprint conflicts`() {
        fixture.reset()
        val now = Instant.parse("2026-07-01T00:00:00Z")
        fixture.executor.transaction { service.acquire(scope, fingerprint, now) }

        val result = fixture.executor.transaction {
            service.acquire(scope, CommandFingerprint.request("meter-register", mapOf("code" to "other")), now)
        }

        result.shouldBeEqualTo(CommandAcquireResult.Conflict)
    }

    @Test
    fun `expired owner is fenced after takeover`() {
        fixture.reset()
        val acquiredAt = Instant.parse("2026-07-01T00:00:00Z")
        val first = fixture.executor.transaction { service.acquire(scope, fingerprint, acquiredAt) } as CommandAcquireResult.Owned
        val second = fixture.executor.transaction {
            service.acquire(scope, fingerprint, acquiredAt.plusSeconds(31))
        }

        val secondOwned = second.shouldBeInstanceOf<CommandAcquireResult.Owned>()
        fixture.executor.transaction { service.succeed(first, 201, "{}", acquiredAt.plusSeconds(31)) }.shouldBeFalse()
        fixture.executor.transaction {
            service.succeed(secondOwned, 201, "{}", acquiredAt.plusSeconds(31))
        }.shouldBeTrue()
    }

    @Test
    fun `stale owner is rejected before event append`() {
        fixture.reset()
        val acquiredAt = Instant.parse("2026-07-01T00:00:00Z")
        val first = fixture.executor.transaction { service.acquire(scope, fingerprint, acquiredAt) } as CommandAcquireResult.Owned
        fixture.executor.transaction { service.acquire(scope, fingerprint, acquiredAt.plusSeconds(31)) }

        assertFailsWith<CommandOwnerLostException> {
            fixture.executor.transaction { service.requireOwnership(first, acquiredAt.plusSeconds(31)) }
        }
    }

    @Test
    fun `event append and terminal response roll back together`() {
        fixture.reset()
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val owned = fixture.executor.transaction { service.acquire(scope, fingerprint, now) } as CommandAcquireResult.Owned
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
        fixture.executor.transaction { service.acquire(scope, fingerprint, now.plusSeconds(1)) }.shouldBeInstanceOf<CommandAcquireResult.InProgress>()
    }

    private fun usageEvent(now: Instant): NewEvent = NewEvent(
        UUID.randomUUID(),
        UsageAccepted("gateway", "atomic", "api_calls", BigDecimal.ONE, now),
        """{"sourceEventId":"atomic","quantity":1}""",
        """{"actor":"tenant-a"}""",
        now,
    )
}
