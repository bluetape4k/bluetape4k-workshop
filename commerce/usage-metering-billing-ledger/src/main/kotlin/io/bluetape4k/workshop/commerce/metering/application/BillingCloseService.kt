package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.chargeAmount
import io.bluetape4k.workshop.commerce.metering.domain.CloseRunState
import io.bluetape4k.workshop.commerce.metering.domain.LedgerEntryType
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRuns
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionEntity
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageCloseQuery
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventEntity
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.*
/**
 * CloseBatchResult은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property runId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 사용량 계량·청구 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property scanned 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property priced 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 * @property unpriced 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 */


data class CloseBatchResult(
    /**
     * `runId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val runId: UUID,
    /**
     * `state`은 사용량 과금·정산에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: CloseRunState,
    /**
     * `scanned`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scanned: Int,
    /**
     * `priced`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val priced: Int,
    /**
     * `unpriced`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val unpriced: Int,
)
/**
 * BillingCloseService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property runs 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property periods 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property usageEvents 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property prices 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 * @property properties 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
class BillingCloseService(
    /**
     * `runs`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val runs: CloseRunRepository,
    /**
     * `periods`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val periods: BillingPeriodRepository,
    /**
     * `usageEvents`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val usageEvents: UsageEventRepository,
    /**
     * `prices`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val prices: PriceVersionRepository,
    /**
     * `properties`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: MeteringProperties,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {
    /**
     * `processNextBatch`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = processNextBatch(tenantId = "tenant-a", runId = UUID.randomUUID(),
     * requestedBatchSize
     * = 25)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param runId 요청 경계에서 검증된 `runId`입니다.
     * @param requestedBatchSize 요청 경계에서 검증된 `requestedBatchSize`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun processNextBatch(tenantId: String, runId: UUID, requestedBatchSize: Int? = null): CloseBatchResult {
        CloseRuns
            .selectAll()
            .where { CloseRuns.id eq runId }
            .forUpdate()
            .singleOrNull() ?: error("close_run_not_found")

        val run = runs.findTenant(runId, tenantId).requireNotNull("CloseRunEntity")
        run.state.requireEquals(CloseRunState.RUNNING.name) { "run.state is not running" }
        val period = periods.findTenant(run.periodId.value, tenantId).requireNotNull("BillingPeriodEntity")
        val batchSize = (requestedBatchSize ?: properties.close.batchSize).coerceAtMost(properties.close.maxBatchSize)
        batchSize.requirePositiveNumber("batchSize")

        val batch = usageEvents.closeBatch(
            UsageCloseQuery(
                tenantId = tenantId,
                startsAt = period.startsAt,
                endsAt = period.endsAt,
                cutoff = run.cutoffReceivedAt,
                lastOccurredAt = run.lastOccurredAt,
                lastId = run.lastUsageEventId,
                limit = batchSize,
            )
        )
        var priced = 0
        var unpriced = 0
        batch.forEach { usage ->
            val price = prices.select(tenantId, usage.meterId.value, period.currency, usage.occurredAt)
            if (price == null) {
                unpriced++
            } else {
                appendCharge(tenantId, period.id.value, period.currency, usage, price)
                priced++
            }
        }
        batch.lastOrNull()?.let {
            run.lastOccurredAt = it.occurredAt
            run.lastUsageEventId = it.id.value
        }
        run.scannedCount += batch.size
        run.pricedCount += priced
        run.unpricedCount += unpriced
        run.checkpointVersion += 1
        run.updatedAt = clock.instant()

        if (batch.size < batchSize) {
            run.state = if (run.unpricedCount == 0L) {
                CloseRunState.READY_TO_FINALIZE.name
            } else {
                CloseRunState.FAILED_VALIDATION.name
            }
            run.lastErrorCategory = if (run.unpricedCount == 0L) null else "PRICE_NOT_FOUND"
        }
        return CloseBatchResult(
            runId = runId,
            state = CloseRunState.valueOf(run.state),
            scanned = batch.size,
            priced = priced,
            unpriced = unpriced
        )
    }

    /**
     * `processAvailable`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = processAvailable(maxRuns = 25)
     * ```
     * @param maxRuns 요청 경계에서 검증된 `maxRuns`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun processAvailable(maxRuns: Int = properties.close.maxBatchesPerTick): Int {
        var processed = 0
        runs.running(maxRuns).forEach { run ->
            processNextBatch(run.tenantId, run.id.value)
            processed++
        }
        return processed
    }

    /**
     * `resumeAfterPriceRepair`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = resumeAfterPriceRepair(tenantId = "tenant-a", runId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param runId 요청 경계에서 검증된 `runId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun resumeAfterPriceRepair(tenantId: String, runId: UUID): CloseBatchResult {
        CloseRuns.selectAll()
            .where { CloseRuns.id eq runId }
            .forUpdate()
            .singleOrNull() ?: error("CloseRuns not found. runId=$runId")

        val run =
            runs.findTenant(runId, tenantId).requireNotNull { "close_run_not_found. runId=$runId, tenantId=$tenantId" }
        run.state.requireEquals(CloseRunState.FAILED_VALIDATION.name) { "run.state is not running" }

        run.state = CloseRunState.RUNNING.name
        run.lastOccurredAt = null
        run.lastUsageEventId = null
        run.scannedCount = 0L
        run.pricedCount = 0L
        run.unpricedCount = 0L
        run.lastErrorCategory = null
        run.checkpointVersion += 1
        run.updatedAt = clock.instant()

        return CloseBatchResult(
            runId = runId,
            state = CloseRunState.RUNNING,
            scanned = 0,
            priced = 0,
            unpriced = 0
        )
    }

    /**
     * appendCharge은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param periodId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param currency 업무 처리에 필요한 currency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param usage 업무 처리에 필요한 usage 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param price 업무 처리에 필요한 price 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun appendCharge(
        tenantId: String,
        periodId: UUID,
        currency: String,
        usage: UsageEventEntity,
        price: PriceVersionEntity,
    ) {
        val amount: BigDecimal = chargeAmount(usage.quantity, price.unitPrice, currency)

        LedgerEntries.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[LedgerEntries.tenantId] = tenantId
            it[postingPeriodId] = periodId
            it[servicePeriodId] = periodId
            it[entryType] = LedgerEntryType.CHARGE.name
            it[sourceReferenceType] = "USAGE_EVENT"
            it[sourceReferenceId] = usage.id.value.toString()
            it[meterId] = usage.meterId.value
            it[priceVersionId] = price.id.value
            it[quantity] = usage.quantity
            it[unitPrice] = price.unitPrice
            it[LedgerEntries.amount] = amount
            it[LedgerEntries.currency] = currency
            it[relatedOriginalEntryId] = null
            it[reason] = null
            it[actor] = "billing-close"
            it[createdAt] = clock.instant()
        }
    }
}
