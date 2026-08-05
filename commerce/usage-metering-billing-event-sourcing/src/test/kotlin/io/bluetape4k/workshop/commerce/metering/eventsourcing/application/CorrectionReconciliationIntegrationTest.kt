package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentDirection
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentPosted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.NewBillingReadModelEntry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionCoordinator
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionEventContext
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionHandlers
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionLease
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionModelType
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrectionReconciliationIntegrationTest {
    private val fixture = EventStoreDatabaseFixture()
    private val eventStore = EventStoreRepository()
    private val codec = DomainEventJsonCodec()
    private val adjustments = AdjustmentCommandService(eventStore, codec)
    private val generations = ProjectionGenerationRepository()
    private val checkpoints = ProjectionCheckpointRepository()
    private val readModels = BillingReadModelRepository()
    private val handlers = ProjectionHandlers(readModels)
    private val coordinator = ProjectionCoordinator(checkpoints)
    private val reconciliation = ReconciliationService(eventStore, codec, generations, readModels)
    private val now = Instant.parse("2026-07-01T00:00:00Z")
    private val query = ReconciliationQuery("tenant-a", "billing", now.minusSeconds(1), now.plusSeconds(3600))

    @Test
    fun `adjustment is append-only idempotent and conflicting reuse is rejected`() {
        fixture.reset()
        val credit = credit("2.00")

        fixture.executor.transaction { adjustments.post("tenant-a", "correction-1", credit, now) }
        fixture.executor.transaction { adjustments.post("tenant-a", "correction-1", credit, now) }.shouldBeFalse()
        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                adjustments.post("tenant-a", "correction-1", credit.copy(amount = BigDecimal.ONE), now)
            }
        }
    }

    @Test
    fun `projection corruption is detected and a clean generation removes the finding`() {
        fixture.reset()
        fixture.executor.transaction {
            val rated = UsageRated(
                "usage-1",
                "api_calls",
                BigDecimal.TEN,
                BigDecimal("0.50"),
                BigDecimal("5.00"),
                "USD",
            )
            eventStore.append(StreamKey("tenant-a", "Rating", "usage-1"), 0, listOf(codec.encode(rated, now)))
            adjustments.post("tenant-a", "correction-1", credit("2.00"), now.plusSeconds(1))
            generations.createInitialActive("billing", 1, now)
        }
        projectGeneration(1)
        fixture.executor.transaction { reconciliation.inspect(query) }.shouldBeNull()

        fixture.executor.transaction {
            readModels.append(
                NewBillingReadModelEntry(
                    "billing", 1, "tenant-a", ProjectionModelType.LEDGER_DEBIT, "corrupt", "corrupt", 99,
                    amount = BigDecimal.ONE, currency = "USD", occurredAt = now,
                ),
            )
        }
        val finding = fixture.executor.transaction { reconciliation.inspect(query) }
        finding.shouldNotBeNull()

        fixture.executor.transaction { generations.createBuilding("billing", 2, 2, now) }
        val lease = projectGeneration(2)
        fixture.executor.transaction { generations.switchActive(lease, 1, now) }
        fixture.executor.transaction { reconciliation.inspect(query) }.shouldBeNull()
        fixture.executor.transaction { reconciliation.isStillCurrent(query, checkNotNull(finding)) }.shouldBeFalse()
    }

    private fun projectGeneration(generation: Int): ProjectionLease {
        val lease = acquire(generation)
        financialEvents().sortedBy(PersistedEvent::globalPosition).forEach { persisted ->
            val event = codec.registry.decode(persisted.eventType, persisted.schemaVersion, persisted.payload)
            val context = ProjectionEventContext(
                "billing", generation, persisted.stream.tenantId, persisted.eventId,
                persisted.globalPosition, persisted.occurredAt,
            )
            fixture.executor.transaction {
                coordinator.apply(lease, persisted.eventId, persisted.globalPosition) {
                    handlers.handle(context, event as DomainEvent)
                }
            }
        }
        return lease
    }

    private fun financialEvents(): List<PersistedEvent> = fixture.executor.transaction {
        listOf("usage.rated", "adjustment.posted").flatMap { type ->
            eventStore.loadByType(EventTypeQuery("tenant-a", type, query.startsAt, query.endsAt, limit = 100))
        }
    }

    private fun acquire(generation: Int): ProjectionLease = fixture.executor.transaction {
        checkpoints.acquireLease("billing", generation, UUID.randomUUID(), now, Duration.ofMinutes(1))
    } ?: error("lease must be acquired")

    private fun credit(amount: String) = AdjustmentPosted(
        AdjustmentDirection.CREDIT,
        BigDecimal(amount),
        "USD",
        "late usage correction",
        "usage-1",
    )
}
