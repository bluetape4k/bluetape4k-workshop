@file:Suppress("MaxLineLength") // Full timestamps remain visible at each billing boundary.

package io.bluetape4k.workshop.commerce.metering

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.application.AdjustmentService
import io.bluetape4k.workshop.commerce.metering.application.BillingCloseService
import io.bluetape4k.workshop.commerce.metering.application.BillingPeriodService
import io.bluetape4k.workshop.commerce.metering.application.InvoiceService
import io.bluetape4k.workshop.commerce.metering.application.PriceActivationService
import io.bluetape4k.workshop.commerce.metering.application.PriceGapRepair
import io.bluetape4k.workshop.commerce.metering.application.ReconciliationService
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionCommand
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionService
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.CloseRunState
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.SourceEventId
import io.bluetape4k.workshop.commerce.metering.domain.SourceSystem
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UnitPrice
import io.bluetape4k.workshop.commerce.metering.domain.UsageQuantity
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunRepository
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceRepository
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeterRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeteringDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PricingScheduleRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * usage 수집부터 historical price 적용, close, invoice, late adjustment,
 * reconciliation까지 하나의 PostgreSQL ledger workflow를 설명하는 통합 테스트입니다.
 *
 * 고정된 [ScenarioClock]으로 `receivedAt`, `occurredAt`, cutoff를 분리해 계산하고,
 * 결과가 immutable ledger provenance로 남는지 확인합니다. 이 test는 normalized
 * authority와 append-only money 규칙을 증명하며, Kafka delivery나 회계/세무 시스템의
 * 운영 적합성을 대신 검증하지 않습니다.
 */
class MeteringEndToEndIntegrationTest {

    companion object: KLogging()

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
    private val clock = ScenarioClock(Instant.parse("2026-07-04T00:00:00Z"))
/**
 * properties은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val properties = MeteringProperties()
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
 * ledger은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val ledger = LedgerEntryRepository()
/**
 * usageEvents은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val usageEvents = UsageEventRepository()
/**
 * activation은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val activation = PriceActivationService(meters, PricingScheduleRepository(), prices, clock)
/**
 * ingestion은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val ingestion = UsageIngestionService(meters, usageEvents, properties, clock)
/**
 * periodService은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val periodService = BillingPeriodService(periods, properties, clock)
/**
 * close은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val close = BillingCloseService(runs, periods, usageEvents, prices, properties, clock)
/**
 * invoices은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val invoices = InvoiceService(InvoiceRepository(), periods, runs, ledger, clock)
/**
 * adjustments은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val adjustments = AdjustmentService(ledger, prices, clock)
/**
 * reconciliation은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reconciliation = ReconciliationService(properties, prices, adjustments, clock)

    @AfterAll
    fun close(): Unit = fixture.close()

    @Test
    @Suppress("LongMethod")
/**
 * 이 시나리오는 "invoice and adjustment" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invoice and adjustment`() {
        fixture.resetAndSeed()
        val tenant = TenantId("tenant-a")
        val meter = MeterCode("api_calls")
        val currency = Currency.getInstance("USD")
        transaction {
            activation.activate(
                tenantId = tenant,
                meterCode = meter,
                currency = currency,
                unitPrice = UnitPrice(BigDecimal("0.125")),
                effectiveFrom = Instant.parse("2026-06-01T00:00:00Z")
            )
        }
        val first = transaction {
            ingest("usage-1", Instant.parse("2026-06-15T00:00:00Z"))
        }
        transaction {
            ingest("usage-1", Instant.parse("2026-06-15T00:00:00Z"))
        }.replayedProducerEvent.shouldBeTrue()

        val servicePeriod = transaction {
            periodService.create(
                tenantId = tenant,
                currency = currency,
                startsAt = Instant.parse("2026-06-01T00:00:00Z"),
                endsAt = Instant.parse("2026-07-01T00:00:00Z")
            )
        }
        val run = transaction {
            periodService.startClose(tenant, servicePeriod)
        }
        transaction {
            close.processNextBatch(tenant.value, run.id)
        }.state shouldBeEqualTo CloseRunState.READY_TO_FINALIZE

        val invoice = transaction {
            invoices.finalize(tenant.value, run.id)
        }
        invoice.total shouldBeEqualTo BigDecimal("1.250000")

        transaction {
            periodService.create(
                tenantId = tenant,
                currency = currency,
                startsAt = Instant.parse("2026-07-01T00:00:00Z"),
                endsAt = Instant.parse("2026-08-01T00:00:00Z")
            )
        }
        val late = transaction {
            ingest("usage-late", Instant.parse("2026-06-20T00:00:00Z"))
        }
        val debit = transaction {
            adjustments.postLateDebit(tenant.value, late.id, currency.currencyCode)
        }

        transaction {
            adjustments.postLateDebit(tenant.value, late.id, currency.currencyCode)
        } shouldBeEqualTo debit

        transaction {
            adjustments.postCredit(tenant.value, debit, "customer goodwill", "operator")
        }

        first.replayedProducerEvent.shouldBeFalse()
        transaction {
            reconciliation.inspect(tenant.value, 200)
        }.findingCount shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "a failed close restarts from its fixed cutoff after an explicit price gap repair" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `a failed close restarts from its fixed cutoff after an explicit price gap repair`() {
        fixture.resetAndSeed()
        val tenant = TenantId("tenant-a")
        val meter = MeterCode("api_calls")
        val currency = Currency.getInstance("USD")

        transaction {
            activation.activate(
                tenantId = tenant,
                meterCode = meter,
                currency = currency,
                unitPrice = UnitPrice(BigDecimal("0.200")),
                effectiveFrom = Instant.parse("2026-07-01T00:00:00Z"),
            )
            ingest("unpriced-usage", Instant.parse("2026-06-15T00:00:00Z"))
        }
        val period = transaction {
            periodService.create(
                tenantId = tenant,
                currency = currency,
                startsAt = Instant.parse("2026-06-01T00:00:00Z"),
                endsAt = Instant.parse("2026-07-01T00:00:00Z"),
            )
        }
        val run = transaction {
            periodService.startClose(tenant, period)
        }
        log.debug { "run=$run" }

        transaction {
            close.processNextBatch(tenant.value, run.id)
        }.state shouldBeEqualTo CloseRunState.FAILED_VALIDATION

        transaction {
            activation.repairGap(
                PriceGapRepair(
                    tenantId = tenant,
                    meterCode = meter,
                    currency = currency,
                    unitPrice = UnitPrice(BigDecimal("0.100")),
                    effectiveFrom = Instant.parse("2026-06-01T00:00:00Z"),
                    effectiveTo = Instant.parse("2026-07-01T00:00:00Z"),
                ),
            )
            close.resumeAfterPriceRepair(tenant.value, run.id)
        }
        transaction {
            close.processNextBatch(tenant.value, run.id)
        }.state shouldBeEqualTo CloseRunState.READY_TO_FINALIZE

        transaction {
            ledger.forPostingPeriod(tenant.value, period).size
        } shouldBeEqualTo 1
    }

/**
 * ingest은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun ingest(sourceId: String, occurredAt: Instant) =
        ingestion.ingest(
            UsageIngestionCommand(
                tenantId = TenantId("tenant-a"),
                sourceSystem = SourceSystem("gateway"),
                sourceEventId = SourceEventId(sourceId),
                meterCode = MeterCode("api_calls"),
                quantity = UsageQuantity(BigDecimal("10")),
                occurredAt = occurredAt,
                actor = "tenant-a",
                correlationId = null,
                requestFingerprint = CommandFingerprint.request("usage", mapOf("sourceEventId" to sourceId)),
            ),
        )

/**
 * transaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T> transaction(crossinline block: () -> T): T =
        fixture.executor.transaction { block() }
}

/**
 * ScenarioClock 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class ScenarioClock(private var current: Instant): Clock() {
/**
 * getZone은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getZone(): ZoneId = ZoneOffset.UTC
/**
 * withZone은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun withZone(zone: ZoneId): Clock = this
/**
 * instant은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun instant(): Instant = current
/**
 * advance은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
