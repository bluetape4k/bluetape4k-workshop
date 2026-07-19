package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.exposed.core.auditable.AuditableUUIDTable
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.domain.CancellationStatus
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventKind
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import io.bluetape4k.workshop.commerce.order.domain.RefundStatus
import io.bluetape4k.workshop.commerce.order.domain.ReservationStatus
import io.bluetape4k.workshop.commerce.order.idempotency.IdempotencyStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

internal object OrderTable : AuditableUUIDTable("commerce_orders") {
    val tenantId = varchar("tenant_id", 80)
    val customerReference = varchar("customer_reference", 120)
    val status = enumerationByName<OrderStatus>("status", 40)
    val revision = long("revision").default(0)
    val providerMode = enumerationByName<ProviderMode>("provider_mode", 40)
    val cancelReason = varchar("cancel_reason", 80).nullable()
}

internal object PaymentAttemptTable : AuditableUUIDTable("commerce_payment_attempts") {
    val orderId = javaUUID("order_id").index()
    val status = enumerationByName<PaymentStatus>("status", 40)
    val revision = long("revision").default(0)
    val providerReference = varchar("provider_reference", 160).nullable()
}

internal object InventoryReservationTable : AuditableUUIDTable("commerce_inventory_reservations") {
    val orderId = javaUUID("order_id").uniqueIndex()
    val status = enumerationByName<ReservationStatus>("status", 40)
    val revision = long("revision").default(0)
    val reasonCode = varchar("reason_code", 80).nullable()
}

internal object FulfillmentGroupTable : AuditableUUIDTable("commerce_fulfillment_groups") {
    val orderId = javaUUID("order_id").index()
    val groupReference = varchar("group_reference", 80)
    val status = enumerationByName<FulfillmentStatus>("status", 40)
    val revision = long("revision").default(0)
    val cancelReason = varchar("cancel_reason", 80).nullable()

    init {
        uniqueIndex(orderId, groupReference)
    }
}

internal object CancellationCaseTable : AuditableUUIDTable("commerce_cancellation_cases") {
    val orderId = javaUUID("order_id").index()
    val lineId = javaUUID("line_id").index()
    val quantity = integer("quantity")
    val status = enumerationByName<CancellationStatus>("status", 40)
    val revision = long("revision").default(0)
    val reasonCode = varchar("reason_code", 80)
}

internal object RefundCaseTable : AuditableUUIDTable("commerce_refund_cases") {
    val orderId = javaUUID("order_id").index()
    val status = enumerationByName<RefundStatus>("status", 40)
    val revision = long("revision").default(0)
    val reasonCode = varchar("reason_code", 80)
}

internal object OrderLineTable : AuditableLongIdTable("commerce_order_lines") {
    val lineId = javaUUID("line_id").uniqueIndex()
    val orderId = javaUUID("order_id").index()
    val sku = varchar("sku", 120)
    val quantity = integer("quantity")
    val unitPrice = decimal("unit_price", 19, 4)
    val cancelledQuantity = integer("cancelled_quantity").default(0)
}

internal object FulfillmentLineTable : Table("commerce_fulfillment_lines") {
    val fulfillmentGroupId = javaUUID("fulfillment_group_id")
    val lineId = javaUUID("line_id")
    val quantity = integer("quantity")

    override val primaryKey = PrimaryKey(fulfillmentGroupId, lineId)
}

internal object LifecycleAuditTable : LongIdTable("commerce_lifecycle_audits") {
    val eventId = javaUUID("event_id").uniqueIndex()
    val orderId = javaUUID("order_id").index()
    val aggregateType = enumerationByName<AggregateType>("aggregate_type", 48)
    val aggregateId = javaUUID("aggregate_id").index()
    val revision = long("revision")
    val fromStatus = varchar("from_status", 48).nullable()
    val toStatus = varchar("to_status", 48)
    val reasonCode = varchar("reason_code", 80).nullable()
    val actorType = varchar("actor_type", 40)
    val occurredAt = timestamp("occurred_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(aggregateType, aggregateId, revision)
    }
}

internal object ProviderEventInboxTable : AuditableLongIdTable("commerce_provider_event_inbox") {
    val provider = varchar("provider", 40)
    val providerEventId = varchar("provider_event_id", 160)
    val paymentAttemptId = javaUUID("payment_attempt_id").index()
    val payloadFingerprint = char("payload_fingerprint", 64)
    val eventKind = enumerationByName<ProviderEventKind>("event_kind", 40)
    val disposition = enumerationByName<ProviderEventDisposition>("disposition", 40)
    val providerOccurredAt = timestamp("provider_occurred_at")

    init {
        uniqueIndex(provider, providerEventId)
    }
}

internal object HttpIdempotencyTable : AuditableLongIdTable("commerce_http_idempotency") {
    val tenantId = varchar("tenant_id", 80)
    val operation = varchar("operation", 80)
    val keyHash = char("key_hash", 64)
    val requestFingerprint = char("request_fingerprint", 64)
    val status = enumerationByName<IdempotencyStatus>("status", 24)
    val ownerToken = javaUUID("owner_token")
    val leaseUntil = timestamp("lease_until")
    val responseStatus = integer("response_status").nullable()
    val responseBody = text("response_body").nullable()
    val expiresAt = timestamp("expires_at")

    init {
        uniqueIndex(tenantId, operation, keyHash)
    }
}

internal val commerceTables =
    arrayOf(
        OrderTable,
        PaymentAttemptTable,
        InventoryReservationTable,
        FulfillmentGroupTable,
        CancellationCaseTable,
        RefundCaseTable,
        OrderLineTable,
        FulfillmentLineTable,
        LifecycleAuditTable,
        ProviderEventInboxTable,
        HttpIdempotencyTable
    )
