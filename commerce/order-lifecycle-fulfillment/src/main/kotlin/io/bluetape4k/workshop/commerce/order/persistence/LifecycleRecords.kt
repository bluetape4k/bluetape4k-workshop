package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
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
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

internal data class OrderRecord(
    val id: UUID,
    val tenantId: String,
    val customerReference: String,
    val status: OrderStatus,
    val revision: Long = 0,
    val providerMode: ProviderMode,
    val cancelReason: String? = null,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class PaymentAttemptRecord(
    val id: UUID,
    val orderId: UUID,
    val status: PaymentStatus,
    val revision: Long = 0,
    val providerReference: String? = null,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class InventoryReservationRecord(
    val id: UUID,
    val orderId: UUID,
    val status: ReservationStatus,
    val revision: Long = 0,
    val reasonCode: String? = null,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class FulfillmentGroupRecord(
    val id: UUID,
    val orderId: UUID,
    val groupReference: String,
    val status: FulfillmentStatus,
    val revision: Long = 0,
    val cancelReason: String? = null,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class CancellationCaseRecord(
    val id: UUID,
    val orderId: UUID,
    val lineId: UUID,
    val quantity: Int,
    val status: CancellationStatus,
    val revision: Long = 0,
    val reasonCode: String,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class RefundCaseRecord(
    val id: UUID,
    val orderId: UUID,
    val status: RefundStatus,
    val revision: Long = 0,
    val reasonCode: String,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OrderLineRecord(
    val id: Long = 0,
    val lineId: UUID,
    val orderId: UUID,
    val sku: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val cancelledQuantity: Int = 0,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class FulfillmentLineRecord(
    val fulfillmentGroupId: UUID,
    val lineId: UUID,
    val quantity: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class LifecycleAuditRecord(
    val id: Long = 0,
    val eventId: UUID,
    val orderId: UUID,
    val aggregateType: AggregateType,
    val aggregateId: UUID,
    val revision: Long,
    val fromStatus: String?,
    val toStatus: String,
    val reasonCode: String?,
    val actorType: String,
    val occurredAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class ProviderEventRecord(
    val id: Long = 0,
    val provider: String,
    val providerEventId: String,
    val paymentAttemptId: UUID,
    val payloadFingerprint: String,
    val eventKind: ProviderEventKind,
    val disposition: ProviderEventDisposition,
    val providerOccurredAt: Instant,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
