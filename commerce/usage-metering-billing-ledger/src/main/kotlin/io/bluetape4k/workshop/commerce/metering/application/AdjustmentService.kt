package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.metering.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.domain.LedgerEntryType
import io.bluetape4k.workshop.commerce.metering.domain.chargeAmount
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodEntity
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriods
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryEntity
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.*
/**
 * AdjustmentService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property ledger 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property prices 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
class AdjustmentService(
    /**
     * `ledger`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val ledger: LedgerEntryRepository,
    /**
     * `prices`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val prices: PriceVersionRepository,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {
    /**
     * `postLateDebit`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = postLateDebit(tenantId = "tenant-a", usageEventId = UUID.randomUUID(),
     * currency =
     * "demo")
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param usageEventId 요청 경계에서 검증된 `usageEventId`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun postLateDebit(tenantId: String, usageEventId: UUID, currency: String): UUID {
        val usage = UsageEventEntity.findById(usageEventId).requireNotNull { "usage_event_not_found" }
        usage.tenantId.requireEquals(tenantId, "usage.tenantId")

        ledger.findSource(
            tenantId = tenantId,
            type = LedgerEntryType.DEBIT_ADJUSTMENT.name,
            sourceType = "USAGE_EVENT",
            sourceId = usageEventId.toString()
        )?.let { return it.id.value }

        val servicePeriod = BillingPeriodEntity.find {
            (BillingPeriods.tenantId eq tenantId) and
                    (BillingPeriods.currency eq currency) and
                    (BillingPeriods.state eq BillingPeriodState.FINALIZED.name) and
                    (BillingPeriods.startsAt lessEq usage.occurredAt) and
                    (BillingPeriods.endsAt greater usage.occurredAt)
        }.singleOrNull() ?: error("finalized_service_period_not_found")

        val postingPeriod = BillingPeriodEntity.find {
            val now = clock.instant()
            (BillingPeriods.tenantId eq tenantId) and
                    (BillingPeriods.currency eq currency) and
                    (BillingPeriods.state eq BillingPeriodState.OPEN.name) and
                    (BillingPeriods.startsAt lessEq now) and
                    (BillingPeriods.endsAt greater now)
        }.singleOrNull() ?: error("open_posting_period_not_found")

        val price = prices.select(tenantId, usage.meterId.value, currency, usage.occurredAt)
            .requireNotNull { "price_not_found" }

        val id = Uuid.V7.nextId()
        LedgerEntries.insertIgnore {
            it[LedgerEntries.id] = id
            it[LedgerEntries.tenantId] = tenantId
            it[postingPeriodId] = postingPeriod.id.value
            it[LedgerEntries.servicePeriodId] = servicePeriod.id.value
            it[entryType] = LedgerEntryType.DEBIT_ADJUSTMENT.name
            it[sourceReferenceType] = "USAGE_EVENT"
            it[sourceReferenceId] = usage.id.value.toString()
            it[meterId] = usage.meterId.value
            it[priceVersionId] = price.id.value
            it[quantity] = usage.quantity
            it[unitPrice] = price.unitPrice
            it[amount] = chargeAmount(usage.quantity, price.unitPrice, currency)
            it[LedgerEntries.currency] = currency
            it[relatedOriginalEntryId] = null
            it[reason] = "late usage received after finalized cutoff"
            it[actor] = "late-usage-adjustment"
            it[createdAt] = clock.instant()
        }
        return ledger.findSource(
            tenantId,
            LedgerEntryType.DEBIT_ADJUSTMENT.name,
            "USAGE_EVENT",
            usageEventId.toString()
        )
            .requireNotNull("LedgeEntryEntity")
            .id.value
    }

    /**
     * `postCredit`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = postCredit(tenantId = "tenant-a", originalEntryId = UUID.randomUUID(), reason
     * =
     * "demo", actor = "operator-a")
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param originalEntryId 요청 경계에서 검증된 `originalEntryId`입니다.
     * @param reason 요청 경계에서 검증된 `reason`입니다.
     * @param actor 요청 경계에서 검증된 `actor`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun postCredit(tenantId: String, originalEntryId: UUID, reason: String, actor: String): UUID {
        reason.requireNotBlank("reason")
        actor.requireNotBlank("actor")
        val original = LedgerEntryEntity.findById(originalEntryId).requireNotNull { "ledger_entry_not_found" }
        original.tenantId.requireEquals(tenantId, "original.tenantId")

        val sourceId = originalEntryId.toString()
        ledger.findSource(tenantId, LedgerEntryType.CREDIT_ADJUSTMENT.name, "LEDGER_ENTRY", sourceId)
            ?.let { return it.id.value }

        val postingPeriod = BillingPeriodEntity.find {
            val now = clock.instant()
            (BillingPeriods.tenantId eq tenantId) and
                    (BillingPeriods.currency eq original.currency) and
                    (BillingPeriods.state eq BillingPeriodState.OPEN.name) and
                    (BillingPeriods.startsAt lessEq now) and
                    (BillingPeriods.endsAt greater now)
        }.singleOrNull() ?: error("open_posting_period_not_found")

        val id = Uuid.V7.nextId()
        LedgerEntries.insertIgnore {
            it[LedgerEntries.id] = id
            it[LedgerEntries.tenantId] = tenantId
            it[postingPeriodId] = postingPeriod.id.value
            it[servicePeriodId] = original.servicePeriodId.value
            it[entryType] = LedgerEntryType.CREDIT_ADJUSTMENT.name
            it[sourceReferenceType] = "LEDGER_ENTRY"
            it[sourceReferenceId] = sourceId
            it[meterId] = original.meterId.value
            it[priceVersionId] = original.priceVersionId.value
            it[quantity] = original.quantity
            it[unitPrice] = original.unitPrice
            it[amount] = original.amount.abs().takeIf { value -> value > BigDecimal.ZERO } ?: error("zero_adjustment")
            it[currency] = original.currency
            it[relatedOriginalEntryId] = original.id.value
            it[LedgerEntries.reason] = reason
            it[LedgerEntries.actor] = actor
            it[createdAt] = clock.instant()
        }
        return ledger.findSource(
            tenantId = tenantId,
            type = LedgerEntryType.CREDIT_ADJUSTMENT.name,
            sourceType = "LEDGER_ENTRY",
            sourceId = sourceId
        )
            .requireNotNull("LedgerEntryEntity")
            .id.value
    }
}
