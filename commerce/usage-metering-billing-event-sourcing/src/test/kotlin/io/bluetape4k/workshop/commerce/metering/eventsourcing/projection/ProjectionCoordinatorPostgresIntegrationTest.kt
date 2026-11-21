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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicInteger


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * ProjectionCoordinatorPostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ProjectionCoordinatorPostgresIntegrationTest {

    companion object: KLogging()

/**
 * fixture은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture = EventStoreDatabaseFixture()
/**
 * generations은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val generations = ProjectionGenerationRepository()
/**
 * checkpoints은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val checkpoints = ProjectionCheckpointRepository()
/**
 * coordinator은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val coordinator = ProjectionCoordinator(checkpoints)
/**
 * rebuilder은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val rebuilder = ProjectionRebuilder(generations, checkpoints)
/**
 * now은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-01T00:00:00Z")

    @Test
/**
 * 이 시나리오는 "building generation remains isolated until a conditional active switch" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "only one initial active generation exists and lease release is fenced" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "expired lease can be taken over and stale owner cannot advance checkpoint" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "marker handler and checkpoint are atomic while duplicate and position gaps are safe" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "failed building generation preserves the healthy active generation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * acquire은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
