package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
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
import java.time.Instant
import java.util.*


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * CorrectionReconciliationIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class CorrectionReconciliationIntegrationTest {

    companion object: KLogging()

/**
 * fixture은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture = EventStoreDatabaseFixture()
/**
 * eventStore은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val eventStore = EventStoreRepository()
/**
 * codec은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val codec = DomainEventJsonCodec()
/**
 * adjustments은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val adjustments = AdjustmentCommandService(eventStore, codec)
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
 * handlers은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val handlers = ProjectionHandlers(readModels)
/**
 * coordinator은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val coordinator = ProjectionCoordinator(checkpoints)
/**
 * reconciliation은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reconciliation = ReconciliationService(eventStore, codec, generations, readModels)
/**
 * now은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-01T00:00:00Z")
/**
 * query은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val query = ReconciliationQuery(
        tenantId = "tenant-a",
        projectionName = "billing",
        startsAt = now.minusSeconds(1),
        endsAt = now.plusSeconds(3600)
    )

    @Test
/**
 * 이 시나리오는 "adjustment is append-only idempotent and conflicting reuse is rejected" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `adjustment is append-only idempotent and conflicting reuse is rejected`() {
        fixture.reset()
        val credit = credit("2.00")

        fixture.executor.transaction {
            adjustments.post(tenantId = "tenant-a", "correction-1", credit, now)
        }
        fixture.executor.transaction {
            adjustments.post(tenantId = "tenant-a", "correction-1", credit, now)
        }.shouldBeFalse()

        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                adjustments.post(
                    tenantId = "tenant-a",
                    adjustmentId = "correction-1",
                    adjustment = credit.copy(amount = BigDecimal.ONE),
                    now = now
                )
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "projection corruption is detected and a clean generation removes the finding" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `projection corruption is detected and a clean generation removes the finding`() {
        fixture.reset()
        fixture.executor.transaction {
            val rated = UsageRated(
                usageEventId = "usage-1",
                meterCode = "api_calls",
                quantity = BigDecimal.TEN,
                unitPrice = BigDecimal("0.50"),
                amount = BigDecimal("5.00"),
                currency = "USD",
            )
            eventStore.append(StreamKey("tenant-a", "Rating", "usage-1"), 0, listOf(codec.encode(rated, now)))
            adjustments.post(tenantId = "tenant-a", "correction-1", credit("2.00"), now + 1.seconds())
            generations.createInitialActive("billing", 1, now)
        }
        projectGeneration(1)
        fixture.executor.transaction { reconciliation.inspect(query) }.shouldBeNull()

        fixture.executor.transaction {
            readModels.append(
                NewBillingReadModelEntry(
                    projectionName = "billing",
                    generation = 1,
                    tenantId = "tenant-a",
                    modelType = ProjectionModelType.LEDGER_DEBIT,
                    entryId = "corrupt",
                    eventType = "corrupt",
                    globalPosition = 99,
                    amount = BigDecimal.ONE,
                    currency = "USD",
                    occurredAt = now,
                ),
            )
        }
        val finding = fixture.executor.transaction {
            reconciliation.inspect(query)
        }.shouldNotBeNull()

        fixture.executor.transaction {
            generations.createBuilding("billing", 2, 2, now)
        }

        val lease = projectGeneration(2)
        fixture.executor.transaction { generations.switchActive(lease, 1, now) }
        fixture.executor.transaction { reconciliation.inspect(query) }.shouldBeNull()
        fixture.executor.transaction { reconciliation.isStillCurrent(query, finding) }.shouldBeFalse()
    }

/**
 * projectGeneration은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun projectGeneration(generation: Int): ProjectionLease {
        val lease = acquire(generation)

        financialEvents()
            .sortedBy(PersistedEvent::globalPosition)
            .forEach { persisted ->
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

/**
 * financialEvents은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun financialEvents(): List<PersistedEvent> = fixture.executor.transaction {
        listOf("usage.rated", "adjustment.posted").flatMap { type ->
/**
 * query은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val query = EventTypeQuery("tenant-a", type, query.startsAt, query.endsAt, limit = 100)
            eventStore.loadByType(query)
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

    @Suppress("SameParameterValue")
/**
 * credit은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun credit(amount: String): AdjustmentPosted =
        AdjustmentPosted(
            direction = AdjustmentDirection.CREDIT,
            amount = amount.toBigDecimal(),
            currency = "USD",
            reason = "late usage correction",
            sourceEventId = "usage-1",
        )
}
