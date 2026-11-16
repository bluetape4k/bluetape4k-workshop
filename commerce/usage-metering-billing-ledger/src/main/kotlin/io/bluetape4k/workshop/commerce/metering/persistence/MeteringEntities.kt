package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityID

class CommandReceiptEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<CommandReceiptEntity>(CommandReceipts)

    var tenantId by CommandReceipts.tenantId
    var operation by CommandReceipts.operation
    var keyDigest by CommandReceipts.keyDigest
    var requestFingerprint by CommandReceipts.requestFingerprint
    var status by CommandReceipts.status
    var ownerToken by CommandReceipts.ownerToken
    var leaseDeadline by CommandReceipts.leaseDeadline
    var retentionDeadline by CommandReceipts.retentionDeadline
    var terminalAt by CommandReceipts.terminalAt
    var terminalHttpStatus by CommandReceipts.terminalHttpStatus
    var terminalResponse by CommandReceipts.terminalResponse
    var createdAt by CommandReceipts.createdAt
    var updatedAt by CommandReceipts.updatedAt
}

class MeterEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<MeterEntity>(Meters)

    var tenantId by Meters.tenantId
    var meterCode by Meters.meterCode
    var unit by Meters.unit
    var description by Meters.description
    var active by Meters.active
    var createdAt by Meters.createdAt
}

class PricingScheduleEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<PricingScheduleEntity>(PricingSchedules)

    var tenantId by PricingSchedules.tenantId
    var meterId by PricingSchedules.meterId
    var currency by PricingSchedules.currency
    var createdAt by PricingSchedules.createdAt
}

class PriceVersionEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<PriceVersionEntity>(PriceVersions)

    var tenantId by PriceVersions.tenantId
    var scheduleId by PriceVersions.scheduleId
    var meterId by PriceVersions.meterId
    var currency by PriceVersions.currency
    var unitPrice by PriceVersions.unitPrice
    var effectiveFrom by PriceVersions.effectiveFrom
    var effectiveTo by PriceVersions.effectiveTo
    var createdAt by PriceVersions.createdAt
}

class UsageEventEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<UsageEventEntity>(UsageEvents)

    var tenantId by UsageEvents.tenantId
    var sourceSystem by UsageEvents.sourceSystem
    var sourceEventId by UsageEvents.sourceEventId
    var requestFingerprint by UsageEvents.requestFingerprint
    var meterId by UsageEvents.meterId
    var quantity by UsageEvents.quantity
    var occurredAt by UsageEvents.occurredAt
    var receivedAt by UsageEvents.receivedAt
    var acceptedActor by UsageEvents.acceptedActor
    var correlationId by UsageEvents.correlationId
}

class BillingCalendarEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<BillingCalendarEntity>(BillingCalendars)

    var tenantId by BillingCalendars.tenantId
    var currency by BillingCalendars.currency
    var createdAt by BillingCalendars.createdAt
}

class BillingPeriodEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<BillingPeriodEntity>(BillingPeriods)

    var tenantId by BillingPeriods.tenantId
    var calendarId by BillingPeriods.calendarId
    var currency by BillingPeriods.currency
    var startsAt by BillingPeriods.startsAt
    var endsAt by BillingPeriods.endsAt
    var allowedLatenessDeadline by BillingPeriods.allowedLatenessDeadline
    var state by BillingPeriods.state
    var version by BillingPeriods.version
    var activeCloseRunId by BillingPeriods.activeCloseRunId
    var cutoffReceivedAt by BillingPeriods.cutoffReceivedAt
    var finalizedAt by BillingPeriods.finalizedAt
    var invoiceId by BillingPeriods.invoiceId
    var createdAt by BillingPeriods.createdAt
}

class CloseRunEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<CloseRunEntity>(CloseRuns)

    var tenantId by CloseRuns.tenantId
    var periodId by CloseRuns.periodId
    var cutoffReceivedAt by CloseRuns.cutoffReceivedAt
    var state by CloseRuns.state
    var lastOccurredAt by CloseRuns.lastOccurredAt
    var lastUsageEventId by CloseRuns.lastUsageEventId
    var scannedCount by CloseRuns.scannedCount
    var pricedCount by CloseRuns.pricedCount
    var unpricedCount by CloseRuns.unpricedCount
    var checkpointVersion by CloseRuns.checkpointVersion
    var lastErrorCategory by CloseRuns.lastErrorCategory
    var createdAt by CloseRuns.createdAt
    var updatedAt by CloseRuns.updatedAt
}

class LedgerEntryEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<LedgerEntryEntity>(LedgerEntries)

    var tenantId by LedgerEntries.tenantId
    var postingPeriodId by LedgerEntries.postingPeriodId
    var servicePeriodId by LedgerEntries.servicePeriodId
    var entryType by LedgerEntries.entryType
    var sourceReferenceType by LedgerEntries.sourceReferenceType
    var sourceReferenceId by LedgerEntries.sourceReferenceId
    var meterId by LedgerEntries.meterId
    var priceVersionId by LedgerEntries.priceVersionId
    var quantity by LedgerEntries.quantity
    var unitPrice by LedgerEntries.unitPrice
    var amount by LedgerEntries.amount
    var currency by LedgerEntries.currency
    var relatedOriginalEntryId by LedgerEntries.relatedOriginalEntryId
    var reason by LedgerEntries.reason
    var actor by LedgerEntries.actor
    var createdAt by LedgerEntries.createdAt
}

class InvoiceEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<InvoiceEntity>(Invoices)

    var tenantId by Invoices.tenantId
    var periodId by Invoices.periodId
    var currency by Invoices.currency
    var totalAmount by Invoices.totalAmount
    var issuedAt by Invoices.issuedAt
    var contentDigest by Invoices.contentDigest
}

class InvoiceLineEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<InvoiceLineEntity>(InvoiceLines)

    var tenantId by InvoiceLines.tenantId
    var invoiceId by InvoiceLines.invoiceId
    var meterId by InvoiceLines.meterId
    var priceVersionId by InvoiceLines.priceVersionId
    var entryType by InvoiceLines.entryType
    var quantity by InvoiceLines.quantity
    var amount by InvoiceLines.amount
    var currency by InvoiceLines.currency
}

class InvoiceLineEntryEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<InvoiceLineEntryEntity>(InvoiceLineEntries)

    var tenantId by InvoiceLineEntries.tenantId
    var invoiceLineId by InvoiceLineEntries.invoiceLineId
    var ledgerEntryId by InvoiceLineEntries.ledgerEntryId
}

class ReconciliationRunEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<ReconciliationRunEntity>(ReconciliationRuns)

    var tenantId by ReconciliationRuns.tenantId
    var scope by ReconciliationRuns.scope
    var startedAt by ReconciliationRuns.startedAt
    var completedAt by ReconciliationRuns.completedAt
    var summaryDigest by ReconciliationRuns.summaryDigest
    var findingCount by ReconciliationRuns.findingCount
}

class ReconciliationFindingEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<ReconciliationFindingEntity>(ReconciliationFindings)

    var tenantId by ReconciliationFindings.tenantId
    var runId by ReconciliationFindings.runId
    var findingType by ReconciliationFindings.findingType
    var resourceType by ReconciliationFindings.resourceType
    var resourceId by ReconciliationFindings.resourceId
    var expectedDigest by ReconciliationFindings.expectedDigest
    var actualDigest by ReconciliationFindings.actualDigest
    var createdAt by ReconciliationFindings.createdAt
}
