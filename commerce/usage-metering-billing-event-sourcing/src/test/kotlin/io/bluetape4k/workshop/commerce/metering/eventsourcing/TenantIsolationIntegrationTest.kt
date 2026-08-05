package io.bluetape4k.workshop.commerce.metering.eventsourcing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.DomainEventJsonCodec
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandAcquireResult
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandReceiptService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionCoordinator
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionEventContext
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionHandlers
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Tag("integration")
class TenantIsolationIntegrationTest {
    private val fixture = EventStoreDatabaseFixture()
    private val eventStore = EventStoreRepository()
    private val codec = DomainEventJsonCodec()
    private val generations = ProjectionGenerationRepository()
    private val checkpoints = ProjectionCheckpointRepository()
    private val readModels = BillingReadModelRepository()
    private val coordinator = ProjectionCoordinator(checkpoints)
    private val handlers = ProjectionHandlers(readModels)

    @Test
    fun `same external identifiers remain isolated across every persistence boundary`() {
        fixture.reset()
        appendRating(TENANT_A, "1.00")
        appendRating(TENANT_B, "2.00")
        acquireSameCommandKeyPerTenant()
        projectBothTenants()

        fixture.executor.transaction { eventStore.load(stream(TENANT_A)).size }.shouldBeEqualTo(1)
        fixture.executor.transaction { eventStore.load(stream(TENANT_B)).size }.shouldBeEqualTo(1)
        BigDecimal("1.00").compareTo(total(TENANT_A)).shouldBeEqualTo(0)
        BigDecimal("2.00").compareTo(total(TENANT_B)).shouldBeEqualTo(0)

        val reconciliation = ReconciliationService(eventStore, codec, generations, readModels)
        fixture.executor.transaction { reconciliation.inspect(query(TENANT_A)) }.shouldBeNull()
        fixture.executor.transaction { reconciliation.inspect(query(TENANT_B)) }.shouldBeNull()
    }

    private fun appendRating(tenantId: String, amount: String) {
        val event = UsageRated(
            usageEventId = SHARED_EXTERNAL_ID,
            meterCode = "api_calls",
            quantity = BigDecimal.ONE,
            unitPrice = BigDecimal(amount),
            amount = BigDecimal(amount),
            currency = "USD",
        )
        fixture.executor.transaction {
            eventStore.append(stream(tenantId), 0, listOf(codec.encode(event, NOW)))
        }
    }

    private fun acquireSameCommandKeyPerTenant() {
        val receipts = CommandReceiptService(CommandReceiptRepository(), LEASE_DURATION, RETENTION)
        val key = CommandFingerprint.key(SHARED_EXTERNAL_ID)
        val fingerprint = CommandFingerprint.request("rate", mapOf("usageId" to SHARED_EXTERNAL_ID))
        fixture.executor.transaction {
            receipts.acquire(CommandScope(TENANT_A, "rate", key), fingerprint, NOW)
                .shouldBeInstanceOf<CommandAcquireResult.Owned>()
            receipts.acquire(CommandScope(TENANT_B, "rate", key), fingerprint, NOW)
                .shouldBeInstanceOf<CommandAcquireResult.Owned>()
        }
    }

    private fun projectBothTenants() {
        val lease = fixture.executor.transaction {
            generations.createInitialActive(PROJECTION, 1, NOW)
            checkNotNull(checkpoints.acquireLease(PROJECTION, 1, UUID.randomUUID(), NOW, LEASE_DURATION))
        }
        val events = fixture.executor.transaction { eventStore.loadAfterGlobalPosition(0, 10) }
        events.forEach { persisted ->
            val event = codec.registry.decode(persisted.eventType, persisted.schemaVersion, persisted.payload)
            fixture.executor.transaction {
                coordinator.apply(lease, persisted.eventId, persisted.globalPosition) {
                    handlers.handle(
                        ProjectionEventContext(
                            PROJECTION,
                            1,
                            persisted.stream.tenantId,
                            persisted.eventId,
                            persisted.globalPosition,
                            persisted.occurredAt,
                        ),
                        event as DomainEvent,
                    )
                }
            }
        }
    }

    private fun stream(tenantId: String): StreamKey = StreamKey(tenantId, "Rating", SHARED_EXTERNAL_ID)

    private fun total(tenantId: String): BigDecimal = fixture.executor.transaction {
        readModels.financialTotal(PROJECTION, 1, tenantId)
    }

    private fun query(tenantId: String): ReconciliationQuery =
        ReconciliationQuery(tenantId, PROJECTION, NOW.minusSeconds(1), NOW.plusSeconds(1))

    private companion object {
        const val TENANT_A = "tenant-a"
        const val TENANT_B = "tenant-b"
        const val PROJECTION = "billing"
        const val SHARED_EXTERNAL_ID = "shared-usage"
        val NOW: Instant = Instant.parse("2026-07-01T00:00:00Z")
        val LEASE_DURATION: Duration = Duration.ofSeconds(30)
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
