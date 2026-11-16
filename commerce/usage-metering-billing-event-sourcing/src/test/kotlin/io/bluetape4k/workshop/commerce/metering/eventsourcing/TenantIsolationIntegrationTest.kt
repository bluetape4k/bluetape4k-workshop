package io.bluetape4k.workshop.commerce.metering.eventsourcing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
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
import java.util.*


@Tag("integration")
class TenantIsolationIntegrationTest {

    private companion object: KLogging() {
        const val TENANT_A = "tenant-a"
        const val TENANT_B = "tenant-b"
        const val PROJECTION = "billing"
        const val SHARED_EXTERNAL_ID = "shared-usage"
        val NOW: Instant = Instant.parse("2026-07-01T00:00:00Z")
        val LEASE_DURATION: Duration = 30.seconds()
        val RETENTION: Duration = 7.days()
    }

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

        fixture.executor.transaction { eventStore.load(stream(TENANT_A)).size } shouldBeEqualTo 1
        fixture.executor.transaction { eventStore.load(stream(TENANT_B)).size } shouldBeEqualTo 1
        total(TENANT_A) shouldBeEqualTo 1.00.toBigDecimal()
        total(TENANT_B) shouldBeEqualTo 2.00.toBigDecimal()

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
            eventStore.append(
                stream = stream(tenantId),
                expectedVersion = 0,
                events = listOf(codec.encode(event, NOW))
            )
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
            checkpoints.acquireLease(
                projectionName = PROJECTION,
                generation = 1,
                ownerToken = UUID.randomUUID(),
                now = NOW,
                duration = LEASE_DURATION
            ).shouldNotBeNull()
        }
        val events = fixture.executor.transaction {
            eventStore.loadAfterGlobalPosition(0, 10)
        }
        events.forEach { persisted ->
            val event = codec.registry.decode(persisted.eventType, persisted.schemaVersion, persisted.payload)

            fixture.executor.transaction {
                coordinator.apply(lease, persisted.eventId, persisted.globalPosition) {
                    handlers.handle(
                        ProjectionEventContext(
                            projectionName = PROJECTION,
                            generation = 1,
                            tenantId = persisted.stream.tenantId,
                            eventId = persisted.eventId,
                            globalPosition = persisted.globalPosition,
                            occurredAt = persisted.occurredAt,
                        ),
                        event as DomainEvent,
                    )
                }
            }
        }
    }

    private fun stream(tenantId: String): StreamKey =
        StreamKey(tenantId, "Rating", SHARED_EXTERNAL_ID)

    private fun total(tenantId: String): BigDecimal =
        fixture.executor.transaction {
            readModels.financialTotal(PROJECTION, 1, tenantId)
        }

    private fun query(tenantId: String): ReconciliationQuery =
        ReconciliationQuery(
            tenantId = tenantId,
            projectionName = PROJECTION,
            startsAt = NOW.minusSeconds(1),
            endsAt = NOW + 1.seconds()
        )
}
