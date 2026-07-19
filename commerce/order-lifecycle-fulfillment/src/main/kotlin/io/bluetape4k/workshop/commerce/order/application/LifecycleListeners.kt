package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.domain.InventoryCommitted
import io.bluetape4k.workshop.commerce.order.domain.OrderPolicy
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderSubmitted
import io.bluetape4k.workshop.commerce.order.domain.PaymentPolicy
import io.bluetape4k.workshop.commerce.order.domain.PaymentProviderEvent
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentSucceeded
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventKind
import io.bluetape4k.workshop.commerce.order.domain.RefundPolicy
import io.bluetape4k.workshop.commerce.order.domain.RefundRequested
import io.bluetape4k.workshop.commerce.order.domain.RefundStatus
import io.bluetape4k.workshop.commerce.order.domain.ReservationPolicy
import io.bluetape4k.workshop.commerce.order.domain.ReservationStatus
import io.bluetape4k.workshop.commerce.order.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.order.payment.PaymentProvider
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRecord
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentLineRecord
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderRepository
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRepository
import io.bluetape4k.workshop.commerce.order.persistence.ProviderEventInboxRepository
import io.bluetape4k.workshop.commerce.order.persistence.ProviderEventRecord
import io.bluetape4k.workshop.commerce.order.persistence.RefundCaseRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

@Service
internal class PaymentEventService(
    private val payments: PaymentAttemptRepository,
    private val inbox: ProviderEventInboxRepository,
    private val audit: LifecycleAuditAppender,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun ingest(event: PaymentProviderEvent): ProviderEventDisposition {
        val payment = payments.findById(event.paymentAttemptId)
        val target = targetStatus(payment.status, event.kind)
        val proposed =
            if (target ==
                null
            ) {
                ProviderEventDisposition.IGNORED_OUT_OF_ORDER
            } else {
                ProviderEventDisposition.APPLIED
            }
        val fingerprint =
            IdempotencyFingerprint.request(
                "${event.paymentAttemptId}|${event.kind}|${event.occurredAt}"
            )
        val disposition =
            inbox.record(
                ProviderEventRecord(
                    provider = "FAKE",
                    providerEventId = event.providerEventId,
                    paymentAttemptId = event.paymentAttemptId,
                    payloadFingerprint = fingerprint,
                    eventKind = event.kind,
                    disposition = proposed,
                    providerOccurredAt = event.occurredAt
                )
            )
        if (disposition != ProviderEventDisposition.APPLIED || target == null) {
            log.info {
                "provider_event_disposition paymentAttemptId=${event.paymentAttemptId} " +
                    "providerEventId=${event.providerEventId} kind=${event.kind} disposition=$disposition"
            }
            return disposition
        }

        PaymentPolicy.transition(payment.status, target)
        if (!payments.transition(payment.id, payment.revision, payment.status, target, event.providerEventId)) {
            inbox.updateDisposition("FAKE", event.providerEventId, ProviderEventDisposition.UNRESOLVED)
            log.warn {
                "provider_event_unresolved paymentAttemptId=${event.paymentAttemptId} " +
                    "providerEventId=${event.providerEventId} expectedRevision=${payment.revision}"
            }
            return ProviderEventDisposition.UNRESOLVED
        }
        audit.append(
            payment.orderId,
            AggregateType.PAYMENT_ATTEMPT,
            payment.id,
            payment.revision + 1,
            payment.status,
            target,
            actor = "PROVIDER"
        )
        if (target == PaymentStatus.SUCCEEDED) {
            events.publishEvent(PaymentSucceeded(Uuid.V7.nextId(), payment.orderId, payment.id, Instant.now(clock)))
        }
        log.info {
            "provider_event_applied orderId=${payment.orderId} paymentAttemptId=${payment.id} " +
                "providerEventId=${event.providerEventId} from=${payment.status} to=$target"
        }
        return disposition
    }

    @Transactional
    fun reconcileDelayedSuccess(paymentAttemptId: UUID): ProviderEventDisposition {
        val payment = payments.findById(paymentAttemptId)
        check(payment.status == PaymentStatus.AUTHORIZING) { "payment is not awaiting delayed success" }
        return ingest(
            PaymentProviderEvent(
                providerEventId = "fake-$paymentAttemptId-success",
                paymentAttemptId = paymentAttemptId,
                kind = ProviderEventKind.SUCCEEDED,
                occurredAt = Instant.now(clock)
            )
        )
    }

    private fun targetStatus(
        current: PaymentStatus,
        kind: ProviderEventKind,
    ): PaymentStatus? =
        when {
            current == PaymentStatus.CREATED && kind == ProviderEventKind.AUTHORIZING -> PaymentStatus.AUTHORIZING
            current == PaymentStatus.AUTHORIZING && kind == ProviderEventKind.SUCCEEDED -> PaymentStatus.SUCCEEDED
            current == PaymentStatus.AUTHORIZING && kind == ProviderEventKind.FAILED -> PaymentStatus.FAILED
            else -> null
        }

    companion object : KLogging()
}

@Component
internal class OrderSubmittedListener(
    private val provider: PaymentProvider,
    private val paymentEvents: PaymentEventService,
    private val orderLifecycleExecutor: ExecutorService,
) {
    @ApplicationModuleListener
    fun on(event: OrderSubmitted) {
        val providerEvents =
            orderLifecycleExecutor
                .submit<List<PaymentProviderEvent>> {
                    provider.authorize(
                        event
                    )
                }.get()
        log.info {
            "payment_authorization_completed orderId=${event.orderId} paymentAttemptId=${event.paymentAttemptId} " +
                "providerMode=${event.providerMode} eventCount=${providerEvents.size}"
        }
        providerEvents.forEach(paymentEvents::ingest)
    }

    companion object : KLogging()
}

@Component
internal class PaymentSucceededListener(
    private val orders: OrderRepository,
    private val reservations: InventoryReservationRepository,
    private val audit: LifecycleAuditAppender,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val failureSwitch: InventoryListenerFailureSwitch,
) {
    @ApplicationModuleListener
    fun on(event: PaymentSucceeded) {
        if (failureSwitch.failNext(event.orderId)) {
            log.warn { "inventory_listener_injected_failure orderId=${event.orderId}" }
            throw IllegalStateException("inventory listener failed for orderId=${event.orderId}")
        }
        val order = orders.findById(event.orderId)
        if (order.status == OrderStatus.SUBMITTED) {
            OrderPolicy.transition(order.status, OrderStatus.ACCEPTED)
            if (orders.transition(order.id, order.revision, order.status, OrderStatus.ACCEPTED)) {
                audit.append(
                    order.id,
                    AggregateType.ORDER,
                    order.id,
                    order.revision + 1,
                    order.status,
                    OrderStatus.ACCEPTED,
                    actor = "SYSTEM"
                )
            }
        }

        val reservation = reservations.findByOrderId(event.orderId) ?: error("reservation missing")
        if (reservation.status == ReservationStatus.HELD) {
            ReservationPolicy.transition(reservation.status, ReservationStatus.COMMITTED)
            if (reservations.transition(
                    reservation.id,
                    reservation.revision,
                    reservation.status,
                    ReservationStatus.COMMITTED
                )
            ) {
                audit.append(
                    event.orderId,
                    AggregateType.INVENTORY_RESERVATION,
                    reservation.id,
                    reservation.revision + 1,
                    reservation.status,
                    ReservationStatus.COMMITTED,
                    actor = "SYSTEM"
                )
                events.publishEvent(
                    InventoryCommitted(Uuid.V7.nextId(), event.orderId, reservation.id, Instant.now(clock))
                )
                log.info {
                    "inventory_committed orderId=${event.orderId} reservationId=${reservation.id} " +
                        "revision=${reservation.revision + 1}"
                }
            }
        }
    }

    companion object : KLogging()
}

@Component
internal class InventoryListenerFailureSwitch {
    private val orderIds = ConcurrentHashMap.newKeySet<UUID>()

    fun failOnce(orderId: UUID) {
        orderIds += orderId
        log.debug { "inventory_listener_failure_armed orderId=$orderId" }
    }

    fun failNext(orderId: UUID): Boolean = orderIds.remove(orderId)

    fun clear() = orderIds.clear()

    companion object : KLogging()
}

@Component
internal class InventoryCommittedListener(
    private val orders: OrderRepository,
    private val lines: OrderLineRepository,
    private val fulfillments: FulfillmentGroupRepository,
    private val fulfillmentLines: FulfillmentLineRepository,
    private val audit: LifecycleAuditAppender,
) {
    @ApplicationModuleListener
    fun on(event: InventoryCommitted) {
        if (fulfillments.findByOrderId(event.orderId).isEmpty()) {
            val orderLines = lines.findByOrderId(event.orderId)
            val groupCount = minOf(2, orderLines.sumOf { it.quantity })
            val groups =
                (0 until groupCount).map { index ->
                    val group =
                        fulfillments.save(
                            FulfillmentGroupRecord(
                                id = Uuid.V7.nextId(),
                                orderId = event.orderId,
                                groupReference = "GROUP-${index + 1}",
                                status = FulfillmentStatus.REQUESTED
                            )
                        )
                    audit.append(
                        event.orderId,
                        AggregateType.FULFILLMENT_GROUP,
                        group.id,
                        0,
                        null,
                        FulfillmentStatus.REQUESTED,
                        actor = "SYSTEM"
                    )
                    group
                }
            var groupIndex = 0
            orderLines.forEach { line ->
                val quantities = IntArray(groupCount)
                repeat(line.quantity) {
                    quantities[groupIndex]++
                    groupIndex = (groupIndex + 1) % groupCount
                }
                quantities.forEachIndexed { index, quantity ->
                    if (quantity > 0) {
                        fulfillmentLines.save(FulfillmentLineRecord(groups[index].id, line.lineId, quantity))
                    }
                }
            }
            log.info {
                "split_fulfillment_created orderId=${event.orderId} groupCount=$groupCount " +
                    "orderLineCount=${orderLines.size} fulfillmentLinkCount=${fulfillmentLines.findByOrderId(
                        event.orderId
                    ).size}"
            }
        }

        val order = orders.findById(event.orderId)
        if (order.status == OrderStatus.ACCEPTED) {
            OrderPolicy.transition(order.status, OrderStatus.FULFILLMENT_IN_PROGRESS)
            if (orders.transition(order.id, order.revision, order.status, OrderStatus.FULFILLMENT_IN_PROGRESS)) {
                audit.append(
                    order.id,
                    AggregateType.ORDER,
                    order.id,
                    order.revision + 1,
                    order.status,
                    OrderStatus.FULFILLMENT_IN_PROGRESS,
                    actor = "SYSTEM"
                )
            }
        }
    }

    companion object : KLogging()
}

@Component
internal class RefundRequestedListener(
    private val refunds: RefundCaseRepository,
    private val audit: LifecycleAuditAppender,
) {
    @ApplicationModuleListener
    fun on(event: RefundRequested) {
        var refund = refunds.findById(event.refundCaseId)
        if (refund.status == RefundStatus.REQUESTED) {
            RefundPolicy.transition(refund.status, RefundStatus.PENDING_PROVIDER)
            if (refunds.transition(refund.id, refund.revision, refund.status, RefundStatus.PENDING_PROVIDER)) {
                audit.append(
                    refund.orderId,
                    AggregateType.REFUND_CASE,
                    refund.id,
                    refund.revision + 1,
                    refund.status,
                    RefundStatus.PENDING_PROVIDER,
                    event.reasonCode,
                    "SYSTEM"
                )
            }
        }
        refund = refunds.findById(event.refundCaseId)
        if (refund.status == RefundStatus.PENDING_PROVIDER) {
            RefundPolicy.transition(refund.status, RefundStatus.SUCCEEDED)
            if (refunds.transition(refund.id, refund.revision, refund.status, RefundStatus.SUCCEEDED)) {
                audit.append(
                    refund.orderId,
                    AggregateType.REFUND_CASE,
                    refund.id,
                    refund.revision + 1,
                    refund.status,
                    RefundStatus.SUCCEEDED,
                    event.reasonCode,
                    "PROVIDER"
                )
                log.info {
                    "refund_succeeded orderId=${refund.orderId} refundCaseId=${refund.id} " +
                        "revision=${refund.revision + 1} reasonCode=${event.reasonCode}"
                }
            }
        }
    }

    companion object : KLogging()
}
