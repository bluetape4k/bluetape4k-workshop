@file:Suppress("TooManyFunctions") // Each function owns one bounded reconciliation invariant.

package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.domain.LedgerEntryType
import io.bluetape4k.workshop.commerce.metering.domain.ReconciliationFindingType
import io.bluetape4k.workshop.commerce.metering.idempotency.Sha256Digest
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodEntity
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriods
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntries
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLines
import io.bluetape4k.workshop.commerce.metering.persistence.Invoices
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryEntity
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionEntity
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationFindingEntity
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationFindings
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationRunEntity
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationRuns
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventEntity
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEvents
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.Clock
import java.util.UUID

data class ReconciliationResult(
    val runId: UUID,
    val findingCount: Int,
    val summaryDigest: String,
    val hasMore: Boolean,
)

private data class FindingCandidate(
    val type: ReconciliationFindingType,
    val resourceType: String,
    val resourceId: String,
    val expectedDigest: String?,
    val actualDigest: String?,
) {
    fun digestLine(): String = "$type|$resourceType|$resourceId|$expectedDigest|$actualDigest"
}

@Service
class ReconciliationService(
    private val properties: MeteringProperties,
    private val prices: PriceVersionRepository,
    private val adjustments: AdjustmentService,
    private val clock: Clock,
) {
    @Transactional
    fun inspect(
        tenantId: String,
        requestedPageSize: Int = properties.reconciliation.pageSize,
    ): ReconciliationResult {
        val pageSize = requestedPageSize.coerceAtMost(properties.reconciliation.maxPageSize)
        require(pageSize > 0) { "invalid_reconciliation_page_size" }
        val now = clock.instant()
        val run = newRun(tenantId, now)
        val candidateLimit = pageSize + 1
        val discovered = buildList {
            addAll(usageFindings(tenantId, candidateLimit - size))
            addAll(ledgerFindings(tenantId, candidateLimit - size))
            addAll(invoiceLineFindings(tenantId, candidateLimit - size))
            addAll(invoiceTotalFindings(tenantId, candidateLimit - size))
        }
        val candidates = discovered.take(pageSize)
        candidates.forEach { candidate -> appendFinding(tenantId, run.id.value, candidate, now) }
        val digest = Sha256Digest.of(candidates.joinToString("\n", transform = FindingCandidate::digestLine)).value
        run.findingCount = candidates.size
        run.summaryDigest = digest
        run.completedAt = now
        return ReconciliationResult(run.id.value, candidates.size, digest, discovered.size > pageSize)
    }

    @Transactional
    fun repairLateUsage(
        tenantId: String,
        findingId: UUID,
        expectedDigest: String,
        currency: String,
    ): UUID {
        val finding = requireNotNull(finding(tenantId, findingId)) { "reconciliation_finding_not_found" }
        require(finding.findingType == ReconciliationFindingType.UNLEDGERED_USAGE_AFTER_CUTOFF.name) {
            "reconciliation_finding_not_repairable"
        }
        require(finding.expectedDigest == expectedDigest) { "reconciliation_finding_stale" }
        val usageId = UUID.fromString(finding.resourceId)
        val usage = requireNotNull(UsageEventEntity.findById(usageId)) { "reconciliation_finding_stale" }
        require(usage.tenantId == tenantId && usageDigest(usage) == expectedDigest) { "reconciliation_finding_stale" }
        val existing = LedgerEntryEntity.find {
            (LedgerEntries.tenantId eq tenantId) and
                (LedgerEntries.entryType eq LedgerEntryType.DEBIT_ADJUSTMENT.name) and
                (LedgerEntries.sourceReferenceType eq USAGE_EVENT) and
                (LedgerEntries.sourceReferenceId eq usageId.toString())
        }.firstOrNull()
        require(existing == null) { "reconciliation_finding_stale" }
        return adjustments.postLateDebit(tenantId, usageId, currency)
    }

    @Transactional(readOnly = true)
    fun finding(tenantId: String, findingId: UUID): ReconciliationFindingEntity? =
        ReconciliationFindingEntity.find {
            (ReconciliationFindings.id eq findingId) and (ReconciliationFindings.tenantId eq tenantId)
        }.firstOrNull()

    private fun newRun(tenantId: String, now: java.time.Instant): ReconciliationRunEntity =
        ReconciliationRunEntity.new(Uuid.V7.nextId()) {
            this.tenantId = tenantId
            scope = "billing-authority"
            startedAt = now
            completedAt = null
            summaryDigest = null
            findingCount = 0
        }

    private fun usageFindings(tenantId: String, limit: Int): List<FindingCandidate> {
        if (limit <= 0) return emptyList()
        val findings = mutableListOf<FindingCandidate>()
        var cursor: UUID? = null
        while (findings.size < limit) {
            val page = UsageEventEntity.find {
                val tenant = UsageEvents.tenantId eq tenantId
                cursor?.let { tenant and (UsageEvents.id greater it) } ?: tenant
            }.orderBy(UsageEvents.id to SortOrder.ASC).limit(SCAN_PAGE_SIZE).toList()
            if (page.isEmpty()) break
            cursor = page.last().id.value
            page.mapNotNullTo(findings) { usage ->
                val servicePeriod = BillingPeriodEntity.find {
                    (BillingPeriods.tenantId eq tenantId) and
                        (BillingPeriods.state eq BillingPeriodState.FINALIZED.name) and
                        (BillingPeriods.startsAt lessEq usage.occurredAt) and
                        (BillingPeriods.endsAt greater usage.occurredAt)
                }.firstOrNull() ?: return@mapNotNullTo null
                val hasLedger = LedgerEntries.selectAll().where {
                    (LedgerEntries.tenantId eq tenantId) and
                        (LedgerEntries.sourceReferenceType eq USAGE_EVENT) and
                        (LedgerEntries.sourceReferenceId eq usage.id.value.toString())
                }.limit(1).any()
                if (hasLedger) return@mapNotNullTo null
                val type =
                    if (usage.receivedAt > requireNotNull(servicePeriod.cutoffReceivedAt)) {
                        ReconciliationFindingType.UNLEDGERED_USAGE_AFTER_CUTOFF
                    } else {
                        ReconciliationFindingType.UNLEDGERED_USAGE
                    }
                FindingCandidate(type, USAGE_EVENT, usage.id.value.toString(), usageDigest(usage), null)
            }
            if (page.size < SCAN_PAGE_SIZE) break
        }
        return findings.take(limit)
    }

    private fun ledgerFindings(tenantId: String, limit: Int): List<FindingCandidate> {
        if (limit <= 0) return emptyList()
        val findings = mutableListOf<FindingCandidate>()
        var cursor: UUID? = null
        while (findings.size < limit) {
            val page = LedgerEntryEntity.find {
                val tenant = LedgerEntries.tenantId eq tenantId
                cursor?.let { tenant and (LedgerEntries.id greater it) } ?: tenant
            }.orderBy(LedgerEntries.id to SortOrder.ASC).limit(SCAN_PAGE_SIZE).toList()
            if (page.isEmpty()) break
            cursor = page.last().id.value
            page.flatMapTo(findings) { entry ->
                listOfNotNull(priceFinding(tenantId, entry), authorityFinding(entry))
            }
            if (page.size < SCAN_PAGE_SIZE) break
        }
        return findings.take(limit)
    }

    @Suppress("ReturnCount") // Fast exits keep the charge-only comparison explicit.
    private fun priceFinding(tenantId: String, entry: LedgerEntryEntity): FindingCandidate? {
        if (entry.entryType != LedgerEntryType.CHARGE.name || entry.sourceReferenceType != USAGE_EVENT) return null
        val usage = UsageEventEntity.findById(UUID.fromString(entry.sourceReferenceId)) ?: return null
        val expected = prices.select(tenantId, usage.meterId.value, entry.currency, usage.occurredAt) ?: return null
        val expectedAmount = usage.quantity.multiply(expected.unitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        if (entry.priceVersionId.value == expected.id.value &&
            entry.unitPrice.compareTo(expected.unitPrice) == 0 &&
            entry.amount.compareTo(expectedAmount) == 0
        ) {
            return null
        }
        return FindingCandidate(
            ReconciliationFindingType.LEDGER_PRICE_MISMATCH,
            LEDGER_ENTRY,
            entry.id.value.toString(),
            Sha256Digest.of("${expected.id.value}|${expected.unitPrice}|$expectedAmount").value,
            ledgerDigest(entry),
        )
    }

    @Suppress("ReturnCount") // Missing authority rows are reported through the same immutable finding.
    private fun authorityFinding(entry: LedgerEntryEntity): FindingCandidate? {
        val posting = BillingPeriodEntity.findById(entry.postingPeriodId.value) ?: return mismatch(entry)
        val service = BillingPeriodEntity.findById(entry.servicePeriodId.value) ?: return mismatch(entry)
        val price = PriceVersionEntity.findById(entry.priceVersionId.value) ?: return mismatch(entry)
        val tenantMatches = setOf(entry.tenantId, posting.tenantId, service.tenantId, price.tenantId).size == 1
        val currencyMatches = setOf(entry.currency, posting.currency, service.currency, price.currency).size == 1
        return if (tenantMatches && currencyMatches) null else mismatch(entry)
    }

    private fun mismatch(entry: LedgerEntryEntity): FindingCandidate =
        FindingCandidate(
            ReconciliationFindingType.TENANT_OR_CURRENCY_MISMATCH,
            LEDGER_ENTRY,
            entry.id.value.toString(),
            Sha256Digest.of("${entry.tenantId}|${entry.currency}").value,
            ledgerDigest(entry),
        )

    private fun invoiceLineFindings(tenantId: String, limit: Int): List<FindingCandidate> {
        if (limit <= 0) return emptyList()
        val findings = mutableListOf<FindingCandidate>()
        var cursor: UUID? = null
        while (findings.size < limit) {
            val page = InvoiceLineEntity.find {
                val tenant = InvoiceLines.tenantId eq tenantId
                cursor?.let { tenant and (InvoiceLines.id greater it) } ?: tenant
            }.orderBy(InvoiceLines.id to SortOrder.ASC).limit(SCAN_PAGE_SIZE).toList()
            if (page.isEmpty()) break
            cursor = page.last().id.value
            page.mapNotNullTo(findings) { line ->
                val entries = InvoiceLineEntries.selectAll()
                    .where { InvoiceLineEntries.invoiceLineId eq line.id.value }
                    .mapNotNull { LedgerEntryEntity.findById(it[InvoiceLineEntries.ledgerEntryId].value) }
                val expectedQuantity = entries.fold(java.math.BigDecimal.ZERO) { sum, entry -> sum + entry.quantity }
                val expectedAmount = entries.fold(java.math.BigDecimal.ZERO) { sum, entry -> sum + entry.amount }
                val expected = Sha256Digest.of(
                    "$expectedQuantity|$expectedAmount|${line.currency}|${entries.size}",
                ).value
                val actual = Sha256Digest.of("${line.quantity}|${line.amount}|${line.currency}|${entries.size}").value
                if (expected == actual) null else FindingCandidate(
                    ReconciliationFindingType.INVOICE_LINE_MISMATCH,
                    "INVOICE_LINE",
                    line.id.value.toString(),
                    expected,
                    actual,
                )
            }
            if (page.size < SCAN_PAGE_SIZE) break
        }
        return findings.take(limit)
    }

    private fun invoiceTotalFindings(tenantId: String, limit: Int): List<FindingCandidate> {
        if (limit <= 0) return emptyList()
        val findings = mutableListOf<FindingCandidate>()
        var cursor: UUID? = null
        while (findings.size < limit) {
            val page = InvoiceEntity.find {
                val tenant = Invoices.tenantId eq tenantId
                cursor?.let { tenant and (Invoices.id greater it) } ?: tenant
            }.orderBy(Invoices.id to SortOrder.ASC).limit(SCAN_PAGE_SIZE).toList()
            if (page.isEmpty()) break
            cursor = page.last().id.value
            page.mapNotNullTo(findings) { invoice ->
                val lineTotal = InvoiceLineEntity.find { InvoiceLines.invoiceId eq invoice.id.value }
                    .fold(java.math.BigDecimal.ZERO) { sum, line -> sum + line.amount }
                if (lineTotal.compareTo(invoice.totalAmount) == 0) null else FindingCandidate(
                    ReconciliationFindingType.INVOICE_TOTAL_MISMATCH,
                    "INVOICE",
                    invoice.id.value.toString(),
                    Sha256Digest.of(lineTotal.toPlainString()).value,
                    Sha256Digest.of(invoice.totalAmount.toPlainString()).value,
                )
            }
            if (page.size < SCAN_PAGE_SIZE) break
        }
        return findings.take(limit)
    }

    private fun appendFinding(tenantId: String, runId: UUID, candidate: FindingCandidate, now: java.time.Instant) {
        ReconciliationFindingEntity.new(Uuid.V7.nextId()) {
            this.tenantId = tenantId
            this.runId = EntityID(runId, ReconciliationRuns)
            findingType = candidate.type.name
            resourceType = candidate.resourceType
            resourceId = candidate.resourceId
            expectedDigest = candidate.expectedDigest
            actualDigest = candidate.actualDigest
            createdAt = now
        }
    }

    private fun usageDigest(usage: UsageEventEntity): String =
        Sha256Digest.of(
            "${usage.id.value}|${usage.tenantId}|${usage.meterId.value}|${usage.quantity}|" +
                "${usage.occurredAt}|${usage.receivedAt}",
        ).value

    private fun ledgerDigest(entry: LedgerEntryEntity): String =
        Sha256Digest.of(
            "${entry.id.value}|${entry.tenantId}|${entry.priceVersionId.value}|${entry.quantity}|" +
                "${entry.unitPrice}|${entry.amount}|${entry.currency}",
        ).value

    private companion object {
        const val MONEY_SCALE = 6
        const val USAGE_EVENT = "USAGE_EVENT"
        const val LEDGER_ENTRY = "LEDGER_ENTRY"
        const val SCAN_PAGE_SIZE = 200
    }
}
