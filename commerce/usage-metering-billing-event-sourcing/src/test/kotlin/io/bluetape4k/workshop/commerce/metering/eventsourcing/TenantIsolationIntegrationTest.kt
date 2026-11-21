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
/**
 * TenantIsolationIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class TenantIsolationIntegrationTest {

    private companion object: KLogging() {
/**
 * TENANT_A은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT_A = "tenant-a"
/**
 * TENANT_B은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT_B = "tenant-b"
/**
 * PROJECTION은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PROJECTION = "billing"
/**
 * SHARED_EXTERNAL_ID은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val SHARED_EXTERNAL_ID = "shared-usage"
/**
 * NOW은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NOW: Instant = Instant.parse("2026-07-01T00:00:00Z")
/**
 * LEASE_DURATION은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val LEASE_DURATION: Duration = 30.seconds()
/**
 * RETENTION은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val RETENTION: Duration = 7.days()
    }

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

    @Test
/**
 * 이 시나리오는 "same external identifiers remain isolated across every persistence boundary" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * appendRating은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * acquireSameCommandKeyPerTenant은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * projectBothTenants은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * stream은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun stream(tenantId: String): StreamKey =
        StreamKey(tenantId, "Rating", SHARED_EXTERNAL_ID)

/**
 * total은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun total(tenantId: String): BigDecimal =
        fixture.executor.transaction {
            readModels.financialTotal(PROJECTION, 1, tenantId)
        }

/**
 * query은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun query(tenantId: String): ReconciliationQuery =
        ReconciliationQuery(
            tenantId = tenantId,
            projectionName = PROJECTION,
            startsAt = NOW.minusSeconds(1),
            endsAt = NOW + 1.seconds()
        )
}
