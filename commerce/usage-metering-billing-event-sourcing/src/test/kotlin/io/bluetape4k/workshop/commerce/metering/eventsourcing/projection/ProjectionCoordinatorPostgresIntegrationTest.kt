package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicInteger


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectionCoordinatorPostgresIntegrationTest {

    companion object: KLogging()

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

        fixture.executor.transaction {
            generations.active("billing")?.generation
        } shouldBeEqualTo 1

        fixture.executor.transaction {
            generations.get("billing", 2)?.state
        } shouldBeEqualTo ProjectionGenerationState.BUILDING

        val lease = acquire(2)
        fixture.executor.transaction {
            coordinator.apply(lease, UUID.randomUUID(), 10) {}
        }
        fixture.executor.transaction {
            rebuilder.catchUpAndSwitch(lease, 1, 20, now)
        }.shouldBeFalse()
        fixture.executor.transaction {
            coordinator.apply(lease, UUID.randomUUID(), 20) {}
        }
        fixture.executor.transaction {
            rebuilder.catchUpAndSwitch(lease, 1, 20, now)
        }.shouldBeTrue()
        fixture.executor.transaction {
            generations.active("billing")?.generation
        } shouldBeEqualTo 2

        fixture.executor.transaction {
            generations.get("billing", 1)?.state
        } shouldBeEqualTo ProjectionGenerationState.RETIRED

        fixture.executor.transaction {
            generations.rollbackActive("billing", 2, 1, now + 1.seconds())
        }.shouldBeTrue()

        fixture.executor.transaction {
            generations.active("billing")?.generation
        } shouldBeEqualTo 1
    }

    @Test
    fun `only one initial active generation exists and lease release is fenced`() {
        fixture.reset()
        fixture.executor.transaction {
            generations.createInitialActive("billing", 1, now)
        }

        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                generations.createInitialActive("billing", 2, now)
            }
        }

        val lease = acquire(1)
        val renewed = fixture.executor.transaction {
            checkpoints.renewLease(lease, now + 10.seconds(), 30.seconds())
        }
        renewed.leaseUntil shouldBeGreaterThan lease.leaseUntil

        fixture.executor.transaction {
            checkpoints.releaseLease(renewed, now + 11.seconds())
        }
        assertFailsWith<StaleProjectionOwnerException> {
            fixture.executor.transaction {
                checkpoints.releaseLease(lease, now + 12.seconds())
            }
        }
    }

    @Test
    fun `expired lease can be taken over and stale owner cannot advance checkpoint`() {
        fixture.reset()
        fixture.executor.transaction {
            generations.createInitialActive("billing", 1, now)
        }
        val stale = acquire(1)
        val current = fixture.executor.transaction {
            checkpoints.acquireLease(
                projectionName = "billing",
                generation = 1,
                ownerToken = UUID.randomUUID(),
                now = now + 31.seconds(),
                duration = 30.seconds()
            )
        } ?: error("takeover must succeed")

        assertFailsWith<StaleProjectionOwnerException> {
            fixture.executor.transaction {
                coordinator.apply(stale, UUID.randomUUID(), 10) {}
            }
        }
        fixture.executor.transaction {
            coordinator.apply(current, UUID.randomUUID(), 10) {}
        }
        fixture.executor.transaction {
            generations.get("billing", 1)?.checkpoint
        } shouldBeEqualTo 10L
    }

    @Test
    fun `marker handler and checkpoint are atomic while duplicate and position gaps are safe`() {
        fixture.reset()
        fixture.executor.transaction { generations.createInitialActive("billing", 1, now) }
        val lease = acquire(1)
        val eventId = UUID.randomUUID()
        val handlerCalls = AtomicInteger()

        fixture.executor.transaction {
            coordinator.apply(lease, eventId, 10) { handlerCalls.incrementAndGet() }
        }
        fixture.executor.transaction {
            coordinator.apply(lease, eventId, 10) { handlerCalls.incrementAndGet() }
        }
        fixture.executor.transaction {
            coordinator.apply(lease, UUID.randomUUID(), 20) { handlerCalls.incrementAndGet() }
        }

        handlerCalls.get() shouldBeEqualTo 2
        fixture.executor.transaction {
            generations.get("billing", 1)?.checkpoint
        } shouldBeEqualTo 20L

        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                coordinator.apply(lease, UUID.randomUUID(), 30) {
                    error("injected crash")
                }
            }
        }
        fixture.executor.transaction {
            generations.get("billing", 1)?.checkpoint
        } shouldBeEqualTo 20L
    }

    @Test
    fun `failed building generation preserves the healthy active generation`() {
        fixture.reset()
        fixture.executor.transaction {
            generations.createInitialActive("billing", 1, now)
        }
        fixture.executor.transaction {
            generations.createBuilding("billing", 2, 100, now)
        }
        val lease = acquire(2)

        fixture.executor.transaction {
            checkpoints.markFailed(lease, 42, "digest", now)
        }

        fixture.executor.transaction {
            generations.active("billing")?.generation
        } shouldBeEqualTo 1

        fixture.executor.transaction {
            generations.get("billing", 2)?.state
        } shouldBeEqualTo ProjectionGenerationState.FAILED

        fixture.executor.transaction {
            generations.switchActive(lease, 1, now)
        }.shouldBeFalse()
    }

    private fun acquire(generation: Int): ProjectionLease =
        fixture.executor.transaction {
            checkpoints.acquireLease(
                projectionName = "billing",
                generation = generation,
                ownerToken = UUID.randomUUID(),
                now = now,
                duration = 30.seconds()
            )
        } ?: error("lease must be acquired")
}
