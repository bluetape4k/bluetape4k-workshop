package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentDirection
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentPosted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceIssued
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionFailureRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.*


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectionRecoveryPostgresIntegrationTest {

    companion object: KLogging()

    private val fixture = EventStoreDatabaseFixture()
    private val generations = ProjectionGenerationRepository()
    private val checkpoints = ProjectionCheckpointRepository()
    private val readModels = BillingReadModelRepository()
    private val failures = ProjectionFailureRepository()
    private val coordinator = ProjectionCoordinator(checkpoints)
    private val handlers = ProjectionHandlers(readModels)
    private val now = Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun `financial read models are deterministic and isolated by tenant and generation`() {
        fixture.reset()
        fixture.executor.transaction {
            generations.createInitialActive("billing", 1, now)
            generations.createBuilding("billing", 2, 4, now)
        }
        val activeLease = acquire(1)
        val buildingLease = acquire(2)
        val usageId = UUID.randomUUID()

        project(activeLease, usageId, 1, "tenant-a", usage())
        project(activeLease, usageId, 1, "tenant-a", usage())
        project(activeLease, UUID.randomUUID(), 2, "tenant-a", rated())
        project(activeLease, UUID.randomUUID(), 3, "tenant-a", invoice())
        project(activeLease, UUID.randomUUID(), 4, "tenant-a", adjustment())
        project(buildingLease, UUID.randomUUID(), 1, "tenant-a", usage())

        val active = fixture.executor.transaction {
            readModels.entries("billing", 1, "tenant-a")
        }
        val building = fixture.executor.transaction {
            readModels.entries("billing", 2, "tenant-a")
        }

        active shouldHaveSize 8
        building shouldHaveSize 2

        val total = fixture.executor.transaction {
            readModels.financialTotal("billing", 1, "tenant-a")
        }
        total shouldBeEqualTo 3.00.toBigDecimal()
        fixture.executor.transaction {
            readModels.entries("billing", 1, "tenant-b")
        }.shouldBeEmpty()
    }

    @Test
    fun `poison event records bounded evidence and fails only its building generation`() {
        fixture.reset()
        fixture.executor.transaction {
            generations.createInitialActive("billing", 1, now)
            generations.createBuilding("billing", 2, 10, now)
        }
        val lease = acquire(2)
        val eventId = UUID.randomUUID()

        fixture.executor.transaction {
            val projectFail = NewProjectionFailure(
                projectionName = "billing",
                generation = 2,
                eventId = eventId,
                eventType = "unknown.event",
                globalPosition = 7,
                errorDigest = "sha256:error",
                failedAt = now
            )
            failures.record(projectFail)
            checkpoints.markFailed(lease, 7, "sha256:error", now)
        }

        val failure = fixture.executor.transaction {
            failures.latest("billing", 2)
        }.shouldNotBeNull()


        failure.eventId.shouldBeEqualTo(eventId)
        failure.errorDigest.shouldBeEqualTo("sha256:error")
        failure.rawPayload.shouldBeNull()

        fixture.executor.transaction {
            generations.active("billing")?.generation
        } shouldBeEqualTo 1
        fixture.executor.transaction {
            generations.get("billing", 2)?.state
        } shouldBeEqualTo ProjectionGenerationState.FAILED

        ProjectionFailureRepository::class.java.methods
            .any { it.name.contains("skip", ignoreCase = true) }
            .shouldBeFalse()
    }

    @Suppress("SameParameterValue")
    private fun project(
        lease: ProjectionLease,
        eventId: UUID,
        position: Long,
        tenantId: String,
        event: DomainEvent,
    ) {
        val context = ProjectionEventContext(
            lease.projectionName,
            lease.generation,
            tenantId,
            eventId,
            position,
            now,
        )
        fixture.executor.transaction {
            coordinator.apply(lease, eventId, position) {
                handlers.handle(context, event)
            }
        }
    }

    private fun acquire(generation: Int): ProjectionLease =
        fixture.executor.transaction {
            checkpoints.acquireLease(
                projectionName = "billing",
                generation = generation,
                ownerToken = UUID.randomUUID(),
                now = now,
                duration = 1.minutes()
            )
        } ?: error("lease must be acquired")

    private fun usage(): UsageAccepted =
        UsageAccepted(
            sourceSystem = "gateway",
            sourceEventId = "source-1",
            meterCode = "api_calls",
            quantity = BigDecimal.TEN,
            occurredAt = now
        )

    private fun rated(): UsageRated =
        UsageRated(
            usageEventId = "source-1",
            meterCode = "api_calls",
            quantity = BigDecimal.TEN,
            unitPrice = BigDecimal("0.50"),
            amount = BigDecimal("5.00"),
            currency = "USD",
        )

    private fun invoice(): InvoiceIssued =
        InvoiceIssued(
            invoiceId = "invoice-1",
            total = BigDecimal("5.00"),
            currency = "USD"
        )

    private fun adjustment(): AdjustmentPosted =
        AdjustmentPosted(
            direction = AdjustmentDirection.CREDIT,
            amount = BigDecimal("2.00"),
            currency = "USD",
            reason = "late",
            sourceEventId = "source-1",
        )
}
