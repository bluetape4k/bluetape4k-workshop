package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectionCoordinatorPostgresIntegrationTest {
    private val fixture = EventStoreDatabaseFixture()
    private val generations = ProjectionGenerationRepository()
    private val checkpoints = ProjectionCheckpointRepository()
    private val coordinator = ProjectionCoordinator(checkpoints)
    private val rebuilder = ProjectionRebuilder(generations, checkpoints)
    private val now = Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun `building generation remains isolated until a conditional active switch`() {
        fixture.reset()
        fixture.executor.transaction { generations.createInitialActive("billing", 1, now) }
        fixture.executor.transaction { rebuilder.begin("billing", 2, 10, now) }

        fixture.executor.transaction { generations.active("billing")?.generation }
            .shouldNotBeNull()
            .shouldBeEqualTo(1)
        fixture.executor.transaction { generations.get("billing", 2)?.state }
            .shouldNotBeNull()
            .shouldBeEqualTo(ProjectionGenerationState.BUILDING)

        val lease = acquire(2)
        fixture.executor.transaction { coordinator.apply(lease, UUID.randomUUID(), 10) {} }
        fixture.executor.transaction { rebuilder.catchUpAndSwitch(lease, 1, 20, now) }.shouldBeFalse()
        fixture.executor.transaction { coordinator.apply(lease, UUID.randomUUID(), 20) {} }
        fixture.executor.transaction { rebuilder.catchUpAndSwitch(lease, 1, 20, now) }.shouldBeTrue()
        fixture.executor.transaction { generations.active("billing")?.generation }
            .shouldNotBeNull()
            .shouldBeEqualTo(2)
        fixture.executor.transaction { generations.get("billing", 1)?.state }
            .shouldNotBeNull()
            .shouldBeEqualTo(ProjectionGenerationState.RETIRED)

        fixture.executor.transaction { generations.rollbackActive("billing", 2, 1, now.plusSeconds(1)) }.shouldBeTrue()
        fixture.executor.transaction { generations.active("billing")?.generation }
            .shouldNotBeNull()
            .shouldBeEqualTo(1)
    }

    @Test
    fun `only one initial active generation exists and lease release is fenced`() {
        fixture.reset()
        fixture.executor.transaction { generations.createInitialActive("billing", 1, now) }
        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction { generations.createInitialActive("billing", 2, now) }
        }

        val lease = acquire(1)
        val renewed = fixture.executor.transaction {
            checkpoints.renewLease(lease, now.plusSeconds(10), Duration.ofSeconds(30))
        }
        (renewed.leaseUntil > lease.leaseUntil).shouldBeTrue()
        fixture.executor.transaction { checkpoints.releaseLease(renewed, now.plusSeconds(11)) }
        assertFailsWith<StaleProjectionOwnerException> {
            fixture.executor.transaction { checkpoints.releaseLease(lease, now.plusSeconds(12)) }
        }
    }

    @Test
    fun `expired lease can be taken over and stale owner cannot advance checkpoint`() {
        fixture.reset()
        fixture.executor.transaction { generations.createInitialActive("billing", 1, now) }
        val stale = acquire(1)
        val current = fixture.executor.transaction {
            checkpoints.acquireLease("billing", 1, UUID.randomUUID(), now.plusSeconds(31), Duration.ofSeconds(30))
        } ?: error("takeover must succeed")

        assertFailsWith<StaleProjectionOwnerException> {
            fixture.executor.transaction { coordinator.apply(stale, UUID.randomUUID(), 10) {} }
        }
        fixture.executor.transaction { coordinator.apply(current, UUID.randomUUID(), 10) {} }
        fixture.executor.transaction { generations.get("billing", 1)?.checkpoint }
            .shouldNotBeNull()
            .shouldBeEqualTo(10L)
    }

    @Test
    fun `marker handler and checkpoint are atomic while duplicate and position gaps are safe`() {
        fixture.reset()
        fixture.executor.transaction { generations.createInitialActive("billing", 1, now) }
        val lease = acquire(1)
        val eventId = UUID.randomUUID()
        val handlerCalls = AtomicInteger()

        fixture.executor.transaction { coordinator.apply(lease, eventId, 10) { handlerCalls.incrementAndGet() } }
        fixture.executor.transaction { coordinator.apply(lease, eventId, 10) { handlerCalls.incrementAndGet() } }
        fixture.executor.transaction {
            coordinator.apply(lease, UUID.randomUUID(), 20) { handlerCalls.incrementAndGet() }
        }

        handlerCalls.get().shouldBeEqualTo(2)
        fixture.executor.transaction { generations.get("billing", 1)?.checkpoint }
            .shouldNotBeNull()
            .shouldBeEqualTo(20L)

        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                coordinator.apply(lease, UUID.randomUUID(), 30) { error("injected crash") }
            }
        }
        fixture.executor.transaction { generations.get("billing", 1)?.checkpoint }
            .shouldNotBeNull()
            .shouldBeEqualTo(20L)
    }

    @Test
    fun `failed building generation preserves the healthy active generation`() {
        fixture.reset()
        fixture.executor.transaction { generations.createInitialActive("billing", 1, now) }
        fixture.executor.transaction { generations.createBuilding("billing", 2, 100, now) }
        val lease = acquire(2)

        fixture.executor.transaction { checkpoints.markFailed(lease, 42, "digest", now) }

        fixture.executor.transaction { generations.active("billing")?.generation }
            .shouldNotBeNull()
            .shouldBeEqualTo(1)
        fixture.executor.transaction { generations.get("billing", 2)?.state }
            .shouldNotBeNull()
            .shouldBeEqualTo(ProjectionGenerationState.FAILED)
        fixture.executor.transaction { generations.switchActive(lease, 1, now) }.shouldBeFalse()
    }

    private fun acquire(generation: Int): ProjectionLease = fixture.executor.transaction {
        checkpoints.acquireLease("billing", generation, UUID.randomUUID(), now, Duration.ofSeconds(30))
    } ?: error("lease must be acquired")
}
