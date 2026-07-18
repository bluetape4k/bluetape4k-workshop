package io.bluetape4k.workshop.commerce.order.domain

import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

internal enum class OrderStatus { SUBMITTED, ACCEPTED, FULFILLMENT_IN_PROGRESS, COMPLETED, CANCELLED }

internal enum class PaymentStatus { CREATED, AUTHORIZING, SUCCEEDED, FAILED, CANCELLED }

internal enum class ReservationStatus { HELD, COMMITTED, RELEASED, EXPIRED, RECONCILIATION_REQUIRED }

internal enum class FulfillmentStatus { REQUESTED, ALLOCATED, PICKING, SHIPPED, DELIVERED, CANCELLED }

internal enum class CancellationStatus { REQUESTED, APPROVED, REJECTED }

internal enum class RefundStatus { REQUESTED, PENDING_PROVIDER, SUCCEEDED, FAILED, MANUAL_REVIEW }

internal enum class AggregateType {
    ORDER,
    PAYMENT_ATTEMPT,
    INVENTORY_RESERVATION,
    FULFILLMENT_GROUP,
    CANCELLATION_CASE,
    REFUND_CASE,
}

internal enum class ProviderMode { SUCCESS, DECLINE, DELAYED_SUCCESS, OUT_OF_ORDER, DUPLICATE_SUCCESS }

internal enum class ProviderEventKind { AUTHORIZING, SUCCEEDED, FAILED, REFUND_SUCCEEDED, REFUND_FAILED }

internal enum class ProviderEventDisposition { APPLIED, DUPLICATE, CONFLICT, IGNORED_OUT_OF_ORDER, UNRESOLVED }

internal data class SubmitOrder(
    val tenantId: String,
    val customerReference: String,
    val lines: List<SubmitOrderLine>,
    val providerMode: ProviderMode = ProviderMode.SUCCESS,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class SubmitOrderLine(
    val sku: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OrderSubmitted(
    val eventId: UUID,
    val orderId: UUID,
    val paymentAttemptId: UUID,
    val providerMode: ProviderMode,
    val occurredAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class PaymentProviderEvent(
    val providerEventId: String,
    val paymentAttemptId: UUID,
    val kind: ProviderEventKind,
    val occurredAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class PaymentSucceeded(
    val eventId: UUID,
    val orderId: UUID,
    val paymentAttemptId: UUID,
    val occurredAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class InventoryCommitted(
    val eventId: UUID,
    val orderId: UUID,
    val reservationId: UUID,
    val occurredAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class RefundRequested(
    val eventId: UUID,
    val orderId: UUID,
    val refundCaseId: UUID,
    val reasonCode: String,
    val occurredAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal class InvalidTransition(
    aggregate: String,
    from: Enum<*>,
    to: Enum<*>,
) : IllegalStateException("Invalid $aggregate transition: $from -> $to")

private fun <S : Enum<S>> transition(
    aggregate: String,
    from: S,
    to: S,
    allowed: Map<S, Set<S>>,
): S {
    if (to !in allowed.getOrDefault(from, emptySet())) {
        throw InvalidTransition(aggregate, from, to)
    }
    return to
}

internal object OrderPolicy {
    private val allowed =
        mapOf(
            OrderStatus.SUBMITTED to setOf(OrderStatus.ACCEPTED, OrderStatus.CANCELLED),
            OrderStatus.ACCEPTED to setOf(OrderStatus.FULFILLMENT_IN_PROGRESS, OrderStatus.CANCELLED),
            OrderStatus.FULFILLMENT_IN_PROGRESS to setOf(OrderStatus.COMPLETED, OrderStatus.CANCELLED)
        )

    fun transition(
        from: OrderStatus,
        to: OrderStatus,
    ) = transition("order", from, to, allowed)
}

internal object PaymentPolicy {
    private val allowed =
        mapOf(
            PaymentStatus.CREATED to setOf(PaymentStatus.AUTHORIZING, PaymentStatus.FAILED, PaymentStatus.CANCELLED),
            PaymentStatus.AUTHORIZING to setOf(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.CANCELLED)
        )

    fun transition(
        from: PaymentStatus,
        to: PaymentStatus,
    ) = transition("payment", from, to, allowed)
}

internal object ReservationPolicy {
    private val allowed =
        mapOf(
            ReservationStatus.HELD to
                setOf(
                    ReservationStatus.COMMITTED,
                    ReservationStatus.RELEASED,
                    ReservationStatus.EXPIRED,
                    ReservationStatus.RECONCILIATION_REQUIRED
                ),
            ReservationStatus.RECONCILIATION_REQUIRED to
                setOf(
                    ReservationStatus.COMMITTED,
                    ReservationStatus.RELEASED
                )
        )

    fun transition(
        from: ReservationStatus,
        to: ReservationStatus,
    ) = transition("reservation", from, to, allowed)
}

internal object FulfillmentPolicy {
    private val allowed =
        mapOf(
            FulfillmentStatus.REQUESTED to setOf(FulfillmentStatus.ALLOCATED, FulfillmentStatus.CANCELLED),
            FulfillmentStatus.ALLOCATED to setOf(FulfillmentStatus.PICKING, FulfillmentStatus.CANCELLED),
            FulfillmentStatus.PICKING to setOf(FulfillmentStatus.SHIPPED, FulfillmentStatus.CANCELLED),
            FulfillmentStatus.SHIPPED to setOf(FulfillmentStatus.DELIVERED)
        )

    fun transition(
        from: FulfillmentStatus,
        to: FulfillmentStatus,
    ) = transition("fulfillment", from, to, allowed)
}

internal object CancellationPolicy {
    private val allowed =
        mapOf(
            CancellationStatus.REQUESTED to setOf(CancellationStatus.APPROVED, CancellationStatus.REJECTED)
        )

    fun transition(
        from: CancellationStatus,
        to: CancellationStatus,
    ) = transition("cancellation", from, to, allowed)
}

internal object RefundPolicy {
    private val allowed =
        mapOf(
            RefundStatus.REQUESTED to setOf(RefundStatus.PENDING_PROVIDER, RefundStatus.MANUAL_REVIEW),
            RefundStatus.PENDING_PROVIDER to
                setOf(
                    RefundStatus.SUCCEEDED,
                    RefundStatus.FAILED,
                    RefundStatus.MANUAL_REVIEW
                ),
            RefundStatus.FAILED to setOf(RefundStatus.PENDING_PROVIDER, RefundStatus.MANUAL_REVIEW)
        )

    fun transition(
        from: RefundStatus,
        to: RefundStatus,
    ) = transition("refund", from, to, allowed)
}
