package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.metering.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.domain.CloseRunState
import io.bluetape4k.workshop.commerce.metering.idempotency.Sha256Digest
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntryEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntries
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLines
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceRepository
import io.bluetape4k.workshop.commerce.metering.persistence.Invoices
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

data class InvoiceView(
    val id: UUID,
    val periodId: UUID,
    val currency: String,
    val total: BigDecimal,
    val digest: String,
)

@Service
class InvoiceService(
    private val invoices: InvoiceRepository,
    private val periods: BillingPeriodRepository,
    private val runs: CloseRunRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) {
    @Transactional
    fun finalize(tenantId: String, runId: UUID): InvoiceView {
        val run = requireNotNull(runs.findTenant(runId, tenantId)) { "close_run_not_found" }
        val period = requireNotNull(periods.findTenant(run.periodId.value, tenantId))
        invoices.findPeriod(tenantId, period.id.value, period.currency)?.let { return it.toView() }
        require(run.state == CloseRunState.READY_TO_FINALIZE.name) { "close_run_not_ready" }
        val entries = ledger.forPostingPeriod(tenantId, period.id.value)
        val total = entries.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount }
        val digest = Sha256Digest.of(entries.joinToString("\n") { "${it.id.value}|${it.amount}|${it.currency}" }).value
        val invoice = InvoiceEntity.new(Uuid.V7.nextId()) {
            this.tenantId = tenantId
            periodId = EntityID(period.id.value, io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriods)
            currency = period.currency
            totalAmount = total
            issuedAt = clock.instant()
            contentDigest = digest
        }
        entries.groupBy { Triple(it.meterId.value, it.priceVersionId.value, it.entryType) }.forEach { (key, group) ->
            val line = InvoiceLineEntity.new(Uuid.V7.nextId()) {
                this.tenantId = tenantId
                invoiceId = EntityID(invoice.id.value, Invoices)
                meterId = EntityID(key.first, io.bluetape4k.workshop.commerce.metering.persistence.Meters)
                priceVersionId = EntityID(
                    key.second,
                    io.bluetape4k.workshop.commerce.metering.persistence.PriceVersions,
                )
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
            InvoiceLineEntries.selectAll().where { InvoiceLineEntries.invoiceLineId eq line.id.value }.count()
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

    private fun InvoiceEntity.toView(): InvoiceView =
        InvoiceView(id.value, periodId.value, currency, totalAmount, contentDigest)
}
