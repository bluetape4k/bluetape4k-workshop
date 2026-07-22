package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandServicePostgresIntegrationTest {
    private val fixture = EventStoreDatabaseFixture()
    private val eventStore = EventStoreRepository()
    private val codec = DomainEventJsonCodec()
    private val meters = MeterCommandService(eventStore, codec)
    private val usages = UsageCommandService(eventStore, codec)
    private val billing = BillingLifecycleCommandService(eventStore, codec)
    private val closer = BillingCloseService(eventStore, codec)
    private val now = Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun `meter registration and price activation use replayed invariants`() {
        fixture.reset()
        fixture.executor.transaction { meters.register("tenant-a", "api_calls", "request", "USD", now) }
        fixture.executor.transaction { meters.activatePrice("tenant-a", "api_calls", BigDecimal("0.10"), "USD", now) }

        assertThrows(IllegalStateException::class.java) {
            fixture.executor.transaction { meters.register("tenant-a", "api_calls", "request", "USD", now) }
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.executor.transaction {
                meters.activatePrice("tenant-a", "api_calls", BigDecimal("0.20"), "USD", now)
            }
        }
    }

    @Test
    fun `twenty concurrent retries create one usage event and conflicts remain visible`() {
        fixture.reset()
        val usage = UsageAccepted("gateway", "source-1", "api_calls", BigDecimal.TEN, now)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        try {
            val results = (1..20).map {
                pool.submit<Boolean> {
                    start.await()
                    fixture.executor.transaction { usages.accept("tenant-a", usage, now).created }
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get() })
        } finally {
            pool.shutdownNow()
        }

        val replay = fixture.executor.transaction { usages.accept("tenant-a", usage, now) }
        assertFalse(replay.created)
        assertEquals(1, fixture.executor.transaction { eventStore.load(replay.stream).size })
        assertThrows(IllegalStateException::class.java) {
            fixture.executor.transaction {
                usages.accept("tenant-a", usage.copy(quantity = BigDecimal.ONE), now)
            }
        }
    }

    @Test
    fun `period close and invoice issue are replay guarded and tenant isolated`() {
        fixture.reset()
        fixture.executor.transaction { billing.open("tenant-a", "2026-07", "USD", now, now.plusSeconds(3600)) }
        fixture.executor.transaction { billing.startClose("tenant-a", "2026-07", now.plusSeconds(3600)) }
        fixture.executor.transaction { billing.issueInvoice("tenant-a", "invoice-1", BigDecimal.TEN, "USD", now) }

        assertFalse(
            fixture.executor.transaction {
                billing.issueInvoice("tenant-a", "invoice-1", BigDecimal.TEN, "USD", now)
            },
        )
        assertEquals(
            0,
            fixture.executor.transaction { eventStore.load(StreamKey("tenant-b", "Invoice", "invoice-1")).size },
        )
    }

    @Test
    fun `restartable close rates usage from event history without a projection`() {
        fixture.reset()
        fixture.executor.transaction { meters.register("tenant-a", "api_calls", "request", "USD", now) }
        fixture.executor.transaction {
            meters.activatePrice("tenant-a", "api_calls", BigDecimal("0.50"), "USD", now.minusSeconds(1))
            billing.open("tenant-a", "2026-07", "USD", now, now.plusSeconds(3600))
            usages.accept("tenant-a", UsageAccepted("gateway", "one", "api_calls", BigDecimal("2"), now), now)
            usages.accept("tenant-a", UsageAccepted("gateway", "two", "api_calls", BigDecimal("3"), now), now)
            billing.startClose("tenant-a", "2026-07", now.plusSeconds(3600))
        }

        assertEquals(
            BillingCloseBatchResult.APPLIED,
            fixture.executor.transaction { closer.closeNextBatch("tenant-a", "2026-07", 1, now) },
        )
        assertEquals(
            BillingCloseBatchResult.APPLIED,
            fixture.executor.transaction { closer.closeNextBatch("tenant-a", "2026-07", 1, now) },
        )
        assertEquals(
            BillingCloseBatchResult.FINALIZED,
            fixture.executor.transaction { closer.closeNextBatch("tenant-a", "2026-07", 1, now) },
        )
        val rated = fixture.executor.transaction {
            eventStore.loadByType(
                EventTypeQuery("tenant-a", "usage.rated", now.minusSeconds(1), now.plusSeconds(3600), limit = 10),
            )
        }
        assertEquals(2, rated.size)
    }

    @Test
    fun `concurrent close retries never duplicate a rated usage`() {
        fixture.reset()
        fixture.executor.transaction {
            meters.register("tenant-a", "api_calls", "request", "USD", now)
            meters.activatePrice("tenant-a", "api_calls", BigDecimal.ONE, "USD", now.minusSeconds(1))
            billing.open("tenant-a", "2026-07", "USD", now, now.plusSeconds(3600))
            usages.accept("tenant-a", UsageAccepted("gateway", "one", "api_calls", BigDecimal.ONE, now), now)
            billing.startClose("tenant-a", "2026-07", now.plusSeconds(3600))
        }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        try {
            val attempts = (1..20).map {
                pool.submit<BillingCloseBatchResult> {
                    start.await()
                    fixture.executor.transaction { closer.closeNextBatch("tenant-a", "2026-07", 10, now) }
                }
            }
            start.countDown()
            attempts.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        fixture.executor.transaction { closer.closeNextBatch("tenant-a", "2026-07", 10, now) }
        val rated = fixture.executor.transaction {
            eventStore.loadByType(
                EventTypeQuery("tenant-a", "usage.rated", now.minusSeconds(1), now.plusSeconds(3600), limit = 10),
            )
        }
        assertEquals(1, rated.size)
    }
}
