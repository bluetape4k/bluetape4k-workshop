package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.metering.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.domain.CloseRunState
import io.bluetape4k.workshop.commerce.metering.idempotency.Sha256Digest
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriods
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunRepository
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntries
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntryEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLines
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceRepository
import io.bluetape4k.workshop.commerce.metering.persistence.Invoices
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersions
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.*
/**
 * InvoiceView은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property periodId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property currency 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 * @property total 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 * @property digest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


data class InvoiceView(
    /**
     * `id`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: UUID,
    /**
     * `periodId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val periodId: UUID,
    /**
     * `currency`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val currency: String,
    /**
     * `total`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val total: BigDecimal,
    /**
     * `digest`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val digest: String,
)
/**
 * InvoiceService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property invoices 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property periods 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property runs 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property ledger 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
class InvoiceService(
    /**
     * `invoices`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val invoices: InvoiceRepository,
    /**
     * `periods`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val periods: BillingPeriodRepository,
    /**
     * `runs`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val runs: CloseRunRepository,
    /**
     * `ledger`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val ledger: LedgerEntryRepository,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {
    /**
     * `finalize`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = finalize(tenantId = "tenant-a", runId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param runId 요청 경계에서 검증된 `runId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun finalize(tenantId: String, runId: UUID): InvoiceView {
        val run = runs.findTenant(runId, tenantId).requireNotNull { "close_run_not_found" }
        val period = periods.findTenant(run.periodId.value, tenantId)
            .requireNotNull { "period not found. periodId=${run.periodId.value}, tenantId=${tenantId}" }
        invoices.findPeriod(tenantId, period.id.value, period.currency)?.let { return it.toView() }
        require(run.state == CloseRunState.READY_TO_FINALIZE.name) { "close_run_not_ready" }

        val entries = ledger.forPostingPeriod(tenantId, period.id.value)
        val total = entries.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount }
        val digest = Sha256Digest(entries.joinToString("\n") { "${it.id.value}|${it.amount}|${it.currency}" }).value

        val invoice = InvoiceEntity.new(Uuid.V7.nextId()) {
            this.tenantId = tenantId
            periodId = EntityID(period.id.value, BillingPeriods)
            currency = period.currency
            totalAmount = total
            issuedAt = clock.instant()
            contentDigest = digest
        }
        entries
            .groupBy { Triple(it.meterId.value, it.priceVersionId.value, it.entryType) }
            .forEach { (key, group) ->
                val line = InvoiceLineEntity.new(Uuid.V7.nextId()) {
                    this.tenantId = tenantId
                    invoiceId = EntityID(invoice.id.value, Invoices)
                    meterId = EntityID(key.first, io.bluetape4k.workshop.commerce.metering.persistence.Meters)
                    priceVersionId = EntityID(key.second, PriceVersions)
                    entryType = key.third
                    quantity = group.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.quantity }
                    amount = group.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount }
                    currency = period.currency
                }
                group.forEach { entry ->
                    InvoiceLineEntryEntity.new(Uuid.V7.nextId()) {
                        this.tenantId = tenantId
                        invoiceLineId = EntityID(line.id.value, InvoiceLines)
                        ledgerEntryId = EntityID(entry.id.value, LedgerEntries)
                    }
                }
            }
        val persistedLines = InvoiceLineEntity.find { InvoiceLines.invoiceId eq invoice.id.value }.toList()
        val persistedTotal = persistedLines.fold(BigDecimal.ZERO) { acc, line -> acc + line.amount }
        val provenanceCount = persistedLines.sumOf { line ->
            InvoiceLineEntries
                .selectAll()
                .where { InvoiceLineEntries.invoiceLineId eq line.id.value }
                .count()
        }
        check(persistedTotal.compareTo(total) == 0) { "invoice_line_total_mismatch" }
        check(provenanceCount == entries.size.toLong()) { "invoice_provenance_count_mismatch" }
        check(persistedLines.all { it.tenantId == tenantId && it.currency == period.currency }) {
            "invoice_authority_mismatch"
        }
        period.invoiceId = invoice.id.value
        period.finalizedAt = clock.instant()
        period.state = BillingPeriodState.FINALIZED.name
        period.version += 1
        run.state = CloseRunState.FINALIZED.name
        run.updatedAt = clock.instant()

        return invoice.toView()
    }

    /**
     * toView은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun InvoiceEntity.toView(): InvoiceView =
        InvoiceView(
            id = id.value,
            periodId = periodId.value,
            currency = currency,
            total = totalAmount,
            digest = contentDigest
        )
}
