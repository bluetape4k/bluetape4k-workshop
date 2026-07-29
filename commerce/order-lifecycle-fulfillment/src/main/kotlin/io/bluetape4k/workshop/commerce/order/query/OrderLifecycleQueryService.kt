package io.bluetape4k.workshop.commerce.order.query

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.domain.CancellationStatus
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.RefundStatus
import io.bluetape4k.workshop.commerce.order.domain.ReservationStatus
import io.bluetape4k.workshop.commerce.order.persistence.CancellationCaseRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRepository
import io.bluetape4k.workshop.commerce.order.persistence.LifecycleAuditRecord
import io.bluetape4k.workshop.commerce.order.persistence.LifecycleAuditRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderRepository
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRepository
import io.bluetape4k.workshop.commerce.order.persistence.ProviderEventInboxRepository
import io.bluetape4k.workshop.commerce.order.persistence.PublicationSnapshotRepository
import io.bluetape4k.workshop.commerce.order.persistence.RefundCaseRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class OrderLifecycleSnapshot(
    val order: OrderSnapshot,
    val lines: List<OrderLineSnapshot>,
    val payment: PaymentSnapshot,
    val reservation: ReservationSnapshot,
    val fulfillments: List<FulfillmentSnapshot>,
    val cancellations: List<CancellationSnapshot>,
    val refunds: List<RefundSnapshot>,
    val audit: List<AuditSnapshot>,
    val operations: OperationsSnapshot,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OrderSnapshot(
    val id: UUID,
    val status: OrderStatus,
    val revision: Long,
    val cancelReason: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OrderLineSnapshot(
    val lineId: UUID,
    val sku: String,
    val quantity: Int,
    val cancelledQuantity: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class PaymentSnapshot(
    val id: UUID,
    val status: PaymentStatus,
    val revision: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class ReservationSnapshot(
    val id: UUID,
    val status: ReservationStatus,
    val revision: Long,
    val reasonCode: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class FulfillmentSnapshot(
    val id: UUID,
    val groupReference: String,
    val status: FulfillmentStatus,
    val revision: Long,
    val cancelReason: String?,
    val lines: List<FulfillmentLineSnapshot>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class FulfillmentLineSnapshot(
    val lineId: UUID,
    val quantity: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class CancellationSnapshot(
    val id: UUID,
    val lineId: UUID,
    val quantity: Int,
    val status: CancellationStatus,
    val revision: Long,
    val reasonCode: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class RefundSnapshot(
    val id: UUID,
    val status: RefundStatus,
    val revision: Long,
    val reasonCode: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class AuditSnapshot(
    val id: Long,
    val aggregateType: AggregateType,
    val aggregateId: UUID,
    val revision: Long,
    val fromStatus: String?,
    val toStatus: String,
    val reasonCode: String?,
    val actorType: String,
    val occurredAt: Instant?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OperationsSnapshot(
    val publications: PublicationSnapshot,
    val unresolvedProviderEvents: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class PublicationSnapshot(
    val published: Int,
    val processing: Int,
    val failed: Int,
    val resubmitted: Int,
    val completed: Int,
    val oldestIncompleteLagSeconds: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Service
internal class OrderLifecycleQueryService(
    private val orders: OrderRepository,
    private val lines: OrderLineRepository,
    private val payments: PaymentAttemptRepository,
    private val reservations: InventoryReservationRepository,
    private val fulfillments: FulfillmentGroupRepository,
    private val fulfillmentLines: FulfillmentLineRepository,
    private val cancellations: CancellationCaseRepository,
    private val refunds: RefundCaseRepository,
    private val audits: LifecycleAuditRepository,
    private val providerEvents: ProviderEventInboxRepository,
    private val publications: PublicationSnapshotRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun snapshot(orderId: UUID): OrderLifecycleSnapshot {
        val order = orders.findById(orderId)
        val payment = payments.findByOrderId(orderId) ?: error("payment attempt missing")
        val reservation = reservations.findByOrderId(orderId) ?: error("inventory reservation missing")
        val links = fulfillmentLines.findByOrderId(orderId).groupBy { it.fulfillmentGroupId }
        return OrderLifecycleSnapshot(
            order = OrderSnapshot(order.id, order.status, order.revision, order.cancelReason),
            lines =
                lines.findByOrderId(orderId).map {
                    OrderLineSnapshot(it.lineId, it.sku, it.quantity, it.cancelledQuantity)
                },
            payment = PaymentSnapshot(payment.id, payment.status, payment.revision),
            reservation =
                ReservationSnapshot(
                    reservation.id,
                    reservation.status,
                    reservation.revision,
                    reservation.reasonCode
                ),
            fulfillments =
                fulfillments.findByOrderId(orderId).map {
                    FulfillmentSnapshot(
                        it.id,
                        it.groupReference,
                        it.status,
                        it.revision,
                        it.cancelReason,
                        links[it.id].orEmpty().map { link -> FulfillmentLineSnapshot(link.lineId, link.quantity) }
                    )
                },
            cancellations =
                cancellations.findByOrderId(orderId).map {
                    CancellationSnapshot(
                        it.id,
                        it.lineId,
                        it.quantity,
                        it.status,
                        it.revision,
                        it.reasonCode
                    )
                },
            refunds =
                refunds.findByOrderId(orderId).map {
                    RefundSnapshot(it.id, it.status, it.revision, it.reasonCode)
                },
            audit = audits.findByOrderId(orderId).map(::toSnapshot),
            operations = OperationsSnapshot(publicationSnapshot(), providerEvents.countUnresolved())
        ).also { snapshot ->
            log.debug {
                "order_snapshot_read orderId=$orderId orderRevision=${snapshot.order.revision} " +
                    "fulfillmentCount=${snapshot.fulfillments.size} auditCount=${snapshot.audit.size} " +
                    "unresolvedProviderEvents=${snapshot.operations.unresolvedProviderEvents}"
            }
        }
    }

    @Transactional(readOnly = true)
    fun auditAfter(
        orderId: UUID,
        afterId: Long,
    ): List<AuditSnapshot> = audits.findByOrderId(orderId, afterId).map(::toSnapshot)

    private fun publicationSnapshot(): PublicationSnapshot {
        val summary = publications.snapshot()
        return PublicationSnapshot(
            published = summary.published,
            processing = summary.processing,
            failed = summary.failed,
            resubmitted = summary.resubmitted,
            completed = summary.completed,
            oldestIncompleteLagSeconds =
                summary.oldestIncomplete
                    ?.let { Duration.between(it, Instant.now(clock)).seconds.coerceAtLeast(0) }
                    ?: 0
        )
    }

    private fun toSnapshot(record: LifecycleAuditRecord) =
        AuditSnapshot(
            id = record.id,
            aggregateType = record.aggregateType,
            aggregateId = record.aggregateId,
            revision = record.revision,
            fromStatus = record.fromStatus,
            toStatus = record.toStatus,
            reasonCode = record.reasonCode,
            actorType = record.actorType,
            occurredAt = record.occurredAt
        )

    companion object : KLogging()
}
