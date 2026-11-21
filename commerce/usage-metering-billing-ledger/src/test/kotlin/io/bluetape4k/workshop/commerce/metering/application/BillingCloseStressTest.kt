@file:Suppress("MagicNumber") // The data volume and batch size are the stress contract under test.

package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.CloseRunState
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UnitPrice
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunRepository
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.MeterRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeteringDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PricingScheduleRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEvents
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

@Tag("stress")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * BillingCloseStressTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class BillingCloseStressTest {

    private companion object: KLogging() {
/**
 * USAGE_COUNT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val USAGE_COUNT = 10_000
    }

/**
 * fixture은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture = MeteringDatabaseFixture()
/**
 * clock은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val clock = Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC)
/**
 * properties은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val properties = MeteringProperties(close = MeteringProperties.Close(batchSize = 500))
/**
 * meters은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val meters = MeterRepository()
/**
 * prices은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val prices = PriceVersionRepository()
/**
 * periods은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val periods = BillingPeriodRepository()
/**
 * runs은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val runs = CloseRunRepository()
/**
 * usage은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val usage = UsageEventRepository()

    @AfterAll
    fun close(): Unit = fixture.close()

/**
 * transaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T> transaction(crossinline block: () -> T): T =
        fixture.executor.transaction { block() }


    @Test
/**
 * 이 시나리오는 "ten thousand usage rows close exactly once across bounded restartable batches" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `ten thousand usage rows close exactly once across bounded restartable batches`() {
        val seed = fixture.resetAndSeed()
        val tenant = TenantId(seed.tenantId)
        val currency = Currency.getInstance(seed.currency)
        transaction {
            PriceActivationService(meters, PricingScheduleRepository(), prices, clock)
                .activate(
                    tenantId = tenant,
                    meterCode = MeterCode(seed.meterCode),
                    currency = currency,
                    unitPrice = UnitPrice(BigDecimal("0.010000")),
                    effectiveFrom = Instant.parse("2026-06-01T00:00:00Z"),
                )
            insertUsage(seed.tenantId, seed.meterId)
        }
        val periodService = BillingPeriodService(periods, properties, clock)
        val run = transaction {
            val periodId = periodService.create(
                tenantId = tenant,
                currency = currency,
                startsAt = Instant.parse("2026-06-01T00:00:00Z"),
                endsAt = Instant.parse("2026-07-01T00:00:00Z"),
            )
            periodService.startClose(tenant, periodId)
        }

        var result = CloseBatchResult(run.id, CloseRunState.RUNNING, 0, 0, 0)

        while (result.state == CloseRunState.RUNNING) {
            val restartedWorker = BillingCloseService(runs, periods, usage, prices, properties, clock)
            result = transaction {
                restartedWorker.processNextBatch(seed.tenantId, run.id)
            }
        }

        result.state shouldBeEqualTo CloseRunState.READY_TO_FINALIZE
        transaction {
            LedgerEntries
                .selectAll()
                .where { LedgerEntries.tenantId eq seed.tenantId }
                .count()
        } shouldBeEqualTo USAGE_COUNT.toLong()

        transaction {
            runs.findTenant(run.id, seed.tenantId)?.scannedCount
        } shouldBeEqualTo USAGE_COUNT.toLong()
    }

/**
 * insertUsage은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun insertUsage(tenantId: String, meterId: UUID) {
        val occurredAt = Instant.parse("2026-06-15T00:00:00Z")

        UsageEvents.batchInsert(0 until USAGE_COUNT) { sequence ->
            val eventId = UUID.nameUUIDFromBytes("usage-$sequence".toUtf8Bytes())
            this[UsageEvents.id] = eventId
            this[UsageEvents.tenantId] = tenantId
            this[UsageEvents.sourceSystem] = "stress-producer"
            this[UsageEvents.sourceEventId] = "usage-$sequence"
            this[UsageEvents.requestFingerprint] = CommandFingerprint.key("usage-$sequence").value
            this[UsageEvents.meterId] = meterId
            this[UsageEvents.quantity] = BigDecimal.ONE
            this[UsageEvents.occurredAt] = occurredAt
            this[UsageEvents.receivedAt] = occurredAt
            this[UsageEvents.acceptedActor] = tenantId
            this[UsageEvents.correlationId] = null
        }
    }

}
