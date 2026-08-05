package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.StreamAppend
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventStorePostgresIntegrationTest {
    private val fixture = EventStoreDatabaseFixture()
    private val repository = EventStoreRepository()

    @Test
    fun `append validates expected version and preserves a hash chain`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Usage", "usage-1")

        val first = fixture.executor.transaction { repository.append(stream, 0, listOf(event("source-1"))) }
        val second = fixture.executor.transaction { repository.append(stream, 1, listOf(event("source-2"))) }

        first.single().streamVersion.shouldBeEqualTo(1L)
        second.single().previousHash.shouldBeEqualTo(first.single().eventHash)
        fixture.executor.transaction { repository.load(stream).map { it.streamVersion } }
            .shouldBeEqualTo(listOf(1L, 2L))
        assertFailsWith<OptimisticConcurrencyException> {
            fixture.executor.transaction { repository.append(stream, 0, listOf(event("stale"))) }
        }
    }

    @Test
    fun `recorded time comes from PostgreSQL instead of the application clock`() {
        fixture.reset()
        val applicationTime = Instant.EPOCH
        val databaseTimedRepository = EventStoreRepository(Clock.fixed(applicationTime, ZoneOffset.UTC))
        val stream = StreamKey("tenant-a", "Usage", "database-time")

        val persisted = fixture.executor.transaction {
            databaseTimedRepository.append(stream, 0, listOf(event("database-time"))).single()
        }

        (persisted.recordedAt > Instant.parse("2020-01-01T00:00:00Z")).shouldBeTrue()
        fixture.executor.transaction { databaseTimedRepository.load(stream).single().recordedAt }
            .shouldBeEqualTo(persisted.recordedAt)
    }

    @Test
    fun `tenant is mandatory when loading a stream`() {
        fixture.reset()
        val tenantA = StreamKey("tenant-a", "Usage", "same-id")
        val tenantB = StreamKey("tenant-b", "Usage", "same-id")
        fixture.executor.transaction { repository.append(tenantA, 0, listOf(event("a"))) }
        fixture.executor.transaction { repository.append(tenantB, 0, listOf(event("b"))) }

        fixture.executor.transaction { repository.load(tenantA).single().payload }.sourceId().shouldBeEqualTo("a")
        fixture.executor.transaction { repository.load(tenantB).single().payload }.sourceId().shouldBeEqualTo("b")
    }

    @Test
    fun `multi-stream append locks and returns streams in canonical order`() {
        fixture.reset()
        val later = StreamKey("tenant-a", "Usage", "z-stream")
        val earlier = StreamKey("tenant-a", "Meter", "a-stream")

        val appended = fixture.executor.transaction {
            repository.appendAll(
                listOf(
                    StreamAppend(later, 0, listOf(event("later"))),
                    StreamAppend(earlier, 0, listOf(event("earlier"))),
                ),
            )
        }

        appended.map { it.stream }.shouldBeEqualTo(listOf(earlier, later))
    }

    @Test
    fun `only one of twenty writers can append the same expected version`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Usage", "contended")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        try {
            val outcomes = (1..20).map { index ->
                pool.submit<Boolean> {
                    start.await()
                    try {
                        fixture.executor.transaction { repository.append(stream, 0, listOf(event("source-$index"))) }
                        true
                    } catch (_: OptimisticConcurrencyException) {
                        false
                    }
                }
            }
            start.countDown()

            outcomes.count { it.get() }.shouldBeEqualTo(1)
            fixture.executor.transaction { repository.load(stream).size }.shouldBeEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `generic repository mutation is blocked for stored events`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Usage", "immutable")
        val stored = fixture.executor.transaction { repository.append(stream, 0, listOf(event("immutable"))).single() }

        assertFailsWith<UnsupportedOperationException> {
            fixture.executor.transaction { repository.save(DomainEventEntity[stored.eventId]) }
        }
    }

    @Test
    fun `global position keyset tolerates sequence gaps`() {
        fixture.reset()
        val firstStream = StreamKey("tenant-a", "Usage", "first")
        val rolledBackStream = StreamKey("tenant-a", "Usage", "rolled-back")
        val laterStream = StreamKey("tenant-a", "Usage", "later")
        val first = fixture.executor.transaction { repository.append(firstStream, 0, listOf(event("first"))).single() }
        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                repository.append(rolledBackStream, 0, listOf(event("rolled-back")))
                error("rollback after consuming a sequence value")
            }
        }
        val later = fixture.executor.transaction { repository.append(laterStream, 0, listOf(event("later"))).single() }

        val page = fixture.executor.transaction { repository.loadAfterGlobalPosition(first.globalPosition, 10) }

        page.map { it.eventId }.shouldBeEqualTo(listOf(later.eventId))
        later.globalPosition.shouldBeGreaterThan(first.globalPosition + 1)
    }

    private fun event(sourceId: String): NewEvent = NewEvent(
        eventId = UUID.randomUUID(),
        event = UsageAccepted(
            sourceSystem = "gateway",
            sourceEventId = sourceId,
            meterCode = "api_calls",
            quantity = BigDecimal.TEN,
            occurredAt = Instant.parse("2026-07-01T00:00:00Z"),
        ),
        payload = """{"sourceEventId":"$sourceId","quantity":10}""",
        metadata = """{"actor":"tenant-a"}""",
        occurredAt = Instant.parse("2026-07-01T00:00:00Z"),
    )

    private fun String.sourceId(): String = substringAfter("sourceEventId\":\"").substringBefore('"')
}
