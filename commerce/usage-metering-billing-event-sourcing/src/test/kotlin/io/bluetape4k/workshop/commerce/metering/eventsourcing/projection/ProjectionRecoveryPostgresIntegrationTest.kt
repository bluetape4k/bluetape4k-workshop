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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.*


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * ProjectionRecoveryPostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ProjectionRecoveryPostgresIntegrationTest {

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
 * readModels은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val readModels = BillingReadModelRepository()
/**
 * failures은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val failures = ProjectionFailureRepository()
/**
 * coordinator은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val coordinator = ProjectionCoordinator(checkpoints)
/**
 * handlers은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val handlers = ProjectionHandlers(readModels)
/**
 * now은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-01T00:00:00Z")

    @Test
/**
 * 이 시나리오는 "financial read models are deterministic and isolated by tenant and generation" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "poison event records bounded evidence and fails only its building generation" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * project은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun project(
        lease: ProjectionLease,
        eventId: UUID,
        position: Long,
        tenantId: String,
        event: DomainEvent,
    ) {
/**
 * context은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
                duration = 1.minutes()
            )
        } ?: error("lease must be acquired")

/**
 * usage은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun usage(): UsageAccepted =
        UsageAccepted(
            sourceSystem = "gateway",
            sourceEventId = "source-1",
            meterCode = "api_calls",
            quantity = BigDecimal.TEN,
            occurredAt = now
        )

/**
 * rated은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun rated(): UsageRated =
        UsageRated(
            usageEventId = "source-1",
            meterCode = "api_calls",
            quantity = BigDecimal.TEN,
            unitPrice = BigDecimal("0.50"),
            amount = BigDecimal("5.00"),
            currency = "USD",
        )

/**
 * invoice은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun invoice(): InvoiceIssued =
        InvoiceIssued(
            invoiceId = "invoice-1",
            total = BigDecimal("5.00"),
            currency = "USD"
        )

/**
 * adjustment은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun adjustment(): AdjustmentPosted =
        AdjustmentPosted(
            direction = AdjustmentDirection.CREDIT,
            amount = BigDecimal("2.00"),
            currency = "USD",
            reason = "late",
            sourceEventId = "source-1",
        )
}
