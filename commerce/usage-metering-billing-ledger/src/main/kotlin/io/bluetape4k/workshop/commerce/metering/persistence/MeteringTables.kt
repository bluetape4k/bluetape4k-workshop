@file:Suppress("MagicNumber") // SQL column sizes and decimal precision are schema declarations.

package io.bluetape4k.workshop.commerce.metering.persistence

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object CommandReceipts : UUIDTable("metering_command_receipt", "receipt_id") {
    val tenantId = varchar("tenant_id", 64)
    val operation = varchar("operation", 64)
    val keyDigest = varchar("key_digest", 64)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val status = varchar("status", 24)
    val ownerToken = javaUUID("owner_token")
    val leaseDeadline = timestamp("lease_deadline")
    val retentionDeadline = timestamp("retention_deadline")
    val terminalAt = timestamp("terminal_at").nullable()
    val terminalHttpStatus = integer("terminal_http_status").nullable()
    val terminalResponse = text("terminal_response").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(tenantId, operation, keyDigest)
        index(false, status, terminalAt, id)
    }
}

object Meters : UUIDTable("metering_meter", "meter_id") {
    val tenantId = varchar("tenant_id", 64)
    val meterCode = varchar("meter_code", 64)
    val unit = varchar("unit", 32)
    val description = varchar("description", 256).nullable()
    val active = bool("active").default(true)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, meterCode)
    }
}

object PricingSchedules : UUIDTable("metering_pricing_schedule", "schedule_id") {
    val tenantId = varchar("tenant_id", 64)
    val meterId = reference("meter_id", Meters, onDelete = ReferenceOption.RESTRICT)
    val currency = varchar("currency", 3)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, meterId, currency)
    }
}

object PriceVersions : UUIDTable("metering_price_version", "price_version_id") {
    val tenantId = varchar("tenant_id", 64)
    val scheduleId = reference("schedule_id", PricingSchedules, onDelete = ReferenceOption.RESTRICT)
    val meterId = reference("meter_id", Meters, onDelete = ReferenceOption.RESTRICT)
    val currency = varchar("currency", 3)
    val unitPrice = decimal("unit_price", 19, 6)
    val effectiveFrom = timestamp("effective_from")
    val effectiveTo = timestamp("effective_to").nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(scheduleId, effectiveFrom)
        index(false, tenantId, meterId, currency, effectiveFrom, effectiveTo)
    }
}

object UsageEvents : UUIDTable("metering_usage_event", "usage_event_id") {
    val tenantId = varchar("tenant_id", 64)
    val sourceSystem = varchar("source_system", 64)
    val sourceEventId = varchar("source_event_id", 128)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val meterId = reference("meter_id", Meters, onDelete = ReferenceOption.RESTRICT)
    val quantity = decimal("quantity", 19, 6)
    val occurredAt = timestamp("occurred_at")
    val receivedAt = timestamp("received_at")
    val acceptedActor = varchar("accepted_actor", 128)
    val correlationId = varchar("correlation_id", 128).nullable()

    init {
        uniqueIndex(tenantId, sourceSystem, sourceEventId)
        index(false, tenantId, occurredAt, id)
        index(false, tenantId, receivedAt)
    }
}

object BillingCalendars : UUIDTable("metering_billing_calendar", "calendar_id") {
    val tenantId = varchar("tenant_id", 64)
    val currency = varchar("currency", 3)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, currency)
    }
}

object BillingPeriods : UUIDTable("metering_billing_period", "period_id") {
    val tenantId = varchar("tenant_id", 64)
    val calendarId = reference("calendar_id", BillingCalendars, onDelete = ReferenceOption.RESTRICT)
    val currency = varchar("currency", 3)
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")
    val allowedLatenessDeadline = timestamp("allowed_lateness_deadline")
    val state = varchar("state", 24)
    val version = long("version").default(0L)
    val activeCloseRunId = javaUUID("active_close_run_id").nullable()
    val cutoffReceivedAt = timestamp("cutoff_received_at").nullable()
    val finalizedAt = timestamp("finalized_at").nullable()
    val invoiceId = javaUUID("invoice_id").nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(calendarId, startsAt, endsAt)
        index(false, tenantId, currency, state, startsAt)
    }
}

object CloseRuns : UUIDTable("metering_close_run", "run_id") {
    val tenantId = varchar("tenant_id", 64)
    val periodId = reference("period_id", BillingPeriods, onDelete = ReferenceOption.RESTRICT)
    val cutoffReceivedAt = timestamp("cutoff_received_at")
    val state = varchar("state", 32)
    val lastOccurredAt = timestamp("last_occurred_at").nullable()
    val lastUsageEventId = javaUUID("last_usage_event_id").nullable()
    val scannedCount = long("scanned_count").default(0L)
    val pricedCount = long("priced_count").default(0L)
    val unpricedCount = long("unpriced_count").default(0L)
    val checkpointVersion = long("checkpoint_version").default(0L)
    val lastErrorCategory = varchar("last_error_category", 64).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(periodId)
        index(false, tenantId, state, updatedAt)
    }
}

object LedgerEntries : UUIDTable("metering_ledger_entry", "ledger_entry_id") {
    val tenantId = varchar("tenant_id", 64)
    val postingPeriodId = reference("posting_period_id", BillingPeriods, onDelete = ReferenceOption.RESTRICT)
    val servicePeriodId = reference("service_period_id", BillingPeriods, onDelete = ReferenceOption.RESTRICT)
    val entryType = varchar("entry_type", 32)
    val sourceReferenceType = varchar("source_reference_type", 32)
    val sourceReferenceId = varchar("source_reference_id", 128)
    val meterId = reference("meter_id", Meters, onDelete = ReferenceOption.RESTRICT)
    val priceVersionId = reference("price_version_id", PriceVersions, onDelete = ReferenceOption.RESTRICT)
    val quantity = decimal("quantity", 19, 6)
    val unitPrice = decimal("unit_price", 19, 6)
    val amount = decimal("amount", 19, 6)
    val currency = varchar("currency", 3)
    val relatedOriginalEntryId = reference(
        "related_original_entry_id",
        LedgerEntries,
        onDelete = ReferenceOption.RESTRICT,
    ).nullable()
    val reason = varchar("reason", 256).nullable()
    val actor = varchar("actor", 128)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, entryType, sourceReferenceType, sourceReferenceId)
        index(false, tenantId, postingPeriodId, currency)
        index(false, tenantId, servicePeriodId)
    }
}

object Invoices : UUIDTable("metering_invoice", "invoice_id") {
    val tenantId = varchar("tenant_id", 64)
    val periodId = reference("period_id", BillingPeriods, onDelete = ReferenceOption.RESTRICT)
    val currency = varchar("currency", 3)
    val totalAmount = decimal("total_amount", 19, 6)
    val issuedAt = timestamp("issued_at")
    val contentDigest = varchar("content_digest", 64)

    init {
        uniqueIndex(tenantId, periodId, currency)
    }
}

object InvoiceLines : UUIDTable("metering_invoice_line", "invoice_line_id") {
    val tenantId = varchar("tenant_id", 64)
    val invoiceId = reference("invoice_id", Invoices, onDelete = ReferenceOption.RESTRICT)
    val meterId = reference("meter_id", Meters, onDelete = ReferenceOption.RESTRICT)
    val priceVersionId = reference("price_version_id", PriceVersions, onDelete = ReferenceOption.RESTRICT)
    val entryType = varchar("entry_type", 32)
    val quantity = decimal("quantity", 19, 6)
    val amount = decimal("amount", 19, 6)
    val currency = varchar("currency", 3)

    init {
        uniqueIndex(invoiceId, meterId, priceVersionId, entryType)
    }
}

object InvoiceLineEntries : UUIDTable("metering_invoice_line_entry", "mapping_id") {
    val tenantId = varchar("tenant_id", 64)
    val invoiceLineId = reference("invoice_line_id", InvoiceLines, onDelete = ReferenceOption.RESTRICT)
    val ledgerEntryId = reference("ledger_entry_id", LedgerEntries, onDelete = ReferenceOption.RESTRICT)

    init {
        uniqueIndex(invoiceLineId, ledgerEntryId)
        uniqueIndex(ledgerEntryId)
    }
}

object ReconciliationRuns : UUIDTable("metering_reconciliation_run", "reconciliation_run_id") {
    val tenantId = varchar("tenant_id", 64)
    val scope = varchar("scope", 128)
    val startedAt = timestamp("started_at")
    val completedAt = timestamp("completed_at").nullable()
    val summaryDigest = varchar("summary_digest", 64).nullable()
    val findingCount = integer("finding_count").default(0)

    init {
        index(false, tenantId, startedAt)
    }
}

object ReconciliationFindings : UUIDTable("metering_reconciliation_finding", "finding_id") {
    val tenantId = varchar("tenant_id", 64)
    val runId = reference("reconciliation_run_id", ReconciliationRuns, onDelete = ReferenceOption.RESTRICT)
    val findingType = varchar("finding_type", 64)
    val resourceType = varchar("resource_type", 64)
    val resourceId = varchar("resource_id", 128)
    val expectedDigest = varchar("expected_digest", 128).nullable()
    val actualDigest = varchar("actual_digest", 128).nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(runId, findingType, resourceType, resourceId)
        index(false, tenantId, findingType, id)
    }
}

val METERING_TABLES: Array<Table> =
    arrayOf(
        CommandReceipts,
        Meters,
        PricingSchedules,
        PriceVersions,
        UsageEvents,
        BillingCalendars,
        BillingPeriods,
        CloseRuns,
        LedgerEntries,
        Invoices,
        InvoiceLines,
        InvoiceLineEntries,
        ReconciliationRuns,
        ReconciliationFindings,
    )
