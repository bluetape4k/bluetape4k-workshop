package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentDirection
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentPosted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceIssued
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class ProjectionModelType {
    USAGE_TIMELINE,
    LEDGER_DEBIT,
    LEDGER_CREDIT,
    INVOICE,
    OPERATOR_TIMELINE,
}

data class ProjectionEventContext(
    val projectionName: String,
    val generation: Int,
    val tenantId: String,
    val eventId: UUID,
    val globalPosition: Long,
    val occurredAt: Instant,
)

data class NewBillingReadModelEntry(
    val projectionName: String,
    val generation: Int,
    val tenantId: String,
    val modelType: ProjectionModelType,
    val entryId: String,
    val eventType: String,
    val globalPosition: Long,
    val quantity: BigDecimal? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val provenance: String? = null,
    val occurredAt: Instant,
)

data class BillingReadModelEntry(
    val modelType: ProjectionModelType,
    val entryId: String,
    val eventType: String,
    val globalPosition: Long,
    val quantity: BigDecimal?,
    val amount: BigDecimal?,
    val currency: String?,
    val provenance: String?,
    val occurredAt: Instant,
)

data class ProjectionFailure(
    val eventId: UUID,
    val eventType: String,
    val globalPosition: Long,
    val errorDigest: String,
    val attemptCount: Int,
) {
    val rawPayload: String? = null
}

data class NewProjectionFailure(
    val projectionName: String,
    val generation: Int,
    val eventId: UUID,
    val eventType: String,
    val globalPosition: Long,
    val errorDigest: String,
    val failedAt: Instant,
)

@Component
class ProjectionHandlers(
    private val repository: BillingReadModelRepository,
) {
    fun handle(context: ProjectionEventContext, event: DomainEvent) {
        when (event) {
            is UsageAccepted -> append(
                context,
                event,
                ProjectionModelType.USAGE_TIMELINE,
                ProjectionValues(quantity = event.quantity, provenance = event.sourceEventId),
            )
            is UsageRated -> append(
                context,
                event,
                ProjectionModelType.LEDGER_DEBIT,
                ProjectionValues(event.quantity, event.amount, event.currency, event.usageEventId),
            )
            is InvoiceIssued -> append(
                context,
                event,
                ProjectionModelType.INVOICE,
                ProjectionValues(amount = event.total, currency = event.currency, provenance = event.invoiceId),
            )
            is AdjustmentPosted -> appendAdjustment(context, event)
            else -> Unit
        }
        append(
            context,
            event,
            ProjectionModelType.OPERATOR_TIMELINE,
            ProjectionValues(provenance = context.eventId.toString()),
        )
    }

    private fun appendAdjustment(context: ProjectionEventContext, event: AdjustmentPosted) {
        val type = if (event.direction == AdjustmentDirection.DEBIT) {
            ProjectionModelType.LEDGER_DEBIT
        } else {
            ProjectionModelType.LEDGER_CREDIT
        }
        append(
            context,
            event,
            type,
            ProjectionValues(amount = event.amount, currency = event.currency, provenance = event.sourceEventId),
        )
    }

    private fun append(
        context: ProjectionEventContext,
        event: DomainEvent,
        modelType: ProjectionModelType,
        values: ProjectionValues,
    ) {
        repository.append(
            NewBillingReadModelEntry(
                projectionName = context.projectionName,
                generation = context.generation,
                tenantId = context.tenantId,
                modelType = modelType,
                entryId = "${context.eventId}:$modelType",
                eventType = event.eventType,
                globalPosition = context.globalPosition,
                quantity = values.quantity,
                amount = values.amount,
                currency = values.currency,
                provenance = values.provenance,
                occurredAt = context.occurredAt,
            ),
        )
    }

    private data class ProjectionValues(
        val quantity: BigDecimal? = null,
        val amount: BigDecimal? = null,
        val currency: String? = null,
        val provenance: String? = null,
    )
}
