package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.workshop.commerce.order.AbstractOrderLifecycleIntegrationTest
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentProviderEvent
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventKind
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import io.bluetape4k.workshop.commerce.order.domain.RefundStatus
import io.bluetape4k.workshop.commerce.order.domain.ReservationStatus
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrder
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrderLine
import io.bluetape4k.workshop.commerce.order.persistence.CancellationCaseRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRepository
import io.bluetape4k.workshop.commerce.order.persistence.LifecycleAuditRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderRepository
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRepository
import io.bluetape4k.workshop.commerce.order.persistence.ProviderEventInboxRepository
import io.bluetape4k.workshop.commerce.order.persistence.RefundCaseRepository
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

internal class OrderLifecycleIntegrationTest(
    private val commands: OrderCommandService,
    private val paymentEvents: PaymentEventService,
    private val orders: OrderRepository,
    private val payments: PaymentAttemptRepository,
    private val providerEvents: ProviderEventInboxRepository,
    private val reservations: InventoryReservationRepository,
    private val fulfillments: FulfillmentGroupRepository,
    private val fulfillmentLines: FulfillmentLineRepository,
    private val cancellations: CancellationCaseRepository,
    private val lines: OrderLineRepository,
    private val refunds: RefundCaseRepository,
    private val audits: LifecycleAuditRepository,
    private val failureSwitch: InventoryListenerFailureSwitch,
    private val reconciliation: PublicationReconciliationService,
    private val publicationRepository: EventPublicationRepository,
    transactionManager: PlatformTransactionManager,
) : AbstractOrderLifecycleIntegrationTest() {
    private val transactions = TransactionTemplate(transactionManager)

    @AfterEach
    fun clearFailure() = failureSwitch.clear()

    @Test
    fun `payment success commits inventory and creates split fulfillment without completing order`() {
        AopUtils.getTargetClass(publicationRepository).name shouldBeEqualTo
            "io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository"
        val submitted = commands.submit(validOrder(ProviderMode.SUCCESS))

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                orders.findById(submitted.orderId).status shouldBeEqualTo OrderStatus.FULFILLMENT_IN_PROGRESS
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.SUCCEEDED
                payments.findById(submitted.paymentAttemptId).revision shouldBeEqualTo 2L
                reservations.findByOrderId(submitted.orderId)!!.status shouldBeEqualTo ReservationStatus.COMMITTED
                fulfillments.findByOrderId(submitted.orderId).size shouldBeEqualTo 2
            }
        }

        readState {
            orders.findById(submitted.orderId).status shouldBeEqualTo OrderStatus.FULFILLMENT_IN_PROGRESS
        }
    }

    @Test
    fun `duplicate and out of order provider events do not reapply terminal payment`() {
        val submitted = commands.submit(validOrder(ProviderMode.OUT_OF_ORDER))

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.SUCCEEDED
            }
        }

        readState {
            val payment = payments.findById(submitted.paymentAttemptId)
            payment.revision shouldBeEqualTo 2L
            audits
                .findByOrderId(submitted.orderId)
                .count { it.aggregateType == AggregateType.PAYMENT_ATTEMPT } shouldBeEqualTo 3
        }
    }

    @Test
    fun `conflicting provider payload remains visible as unresolved evidence`() {
        val submitted = commands.submit(validOrder(ProviderMode.DELAYED_SUCCESS))
        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.AUTHORIZING
            }
        }

        val providerEventId = "manual-conflict-${submitted.paymentAttemptId}"
        paymentEvents.ingest(
            PaymentProviderEvent(
                providerEventId,
                submitted.paymentAttemptId,
                ProviderEventKind.SUCCEEDED,
                Instant.parse("2026-07-18T00:00:10Z")
            )
        ) shouldBeEqualTo io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition.APPLIED
        paymentEvents.ingest(
            PaymentProviderEvent(
                providerEventId,
                submitted.paymentAttemptId,
                ProviderEventKind.FAILED,
                Instant.parse("2026-07-18T00:00:11Z")
            )
        ) shouldBeEqualTo io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition.CONFLICT

        readState {
            providerEvents.countUnresolved() shouldBeEqualTo 1L
            providerEvents.find("FAKE", providerEventId)!!.disposition shouldBeEqualTo
                io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition.CONFLICT
        }
    }

    @Test
    fun `failed publication remains visible and replay completes inventory exactly once`() {
        val submitted = commands.submit(validOrder(ProviderMode.DELAYED_SUCCESS))
        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.AUTHORIZING
            }
        }

        failureSwitch.failOnce(submitted.orderId)
        paymentEvents.ingest(
            PaymentProviderEvent(
                providerEventId = "manual-success-${submitted.paymentAttemptId}",
                paymentAttemptId = submitted.paymentAttemptId,
                kind = ProviderEventKind.SUCCEEDED,
                occurredAt = Instant.parse("2026-07-18T00:00:10Z")
            )
        )

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                publicationRepository.countByStatus(EventPublication.Status.FAILED) shouldBeGreaterThan 0
                reservations.findByOrderId(submitted.orderId)!!.status shouldBeEqualTo ReservationStatus.HELD
            }
        }

        reconciliation.replayFailed(1)

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                reservations.findByOrderId(submitted.orderId)!!.status shouldBeEqualTo ReservationStatus.COMMITTED
                fulfillments.findByOrderId(submitted.orderId).size shouldBeEqualTo 2
            }
        }
    }

    @Test
    fun `delayed payment success is reconciled through a deterministic provider event`() {
        val submitted = commands.submit(validOrder(ProviderMode.DELAYED_SUCCESS))

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.AUTHORIZING
            }
        }

        paymentEvents.reconcileDelayedSuccess(submitted.paymentAttemptId) shouldBeEqualTo
            io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition.APPLIED

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.SUCCEEDED
                fulfillments.findByOrderId(submitted.orderId).size shouldBeEqualTo 2
            }
        }
    }

    @Test
    fun `one line split across shipped and unshipped groups can cancel only its remaining quantity`() {
        val submitted = commands.submit(validOrder(ProviderMode.SUCCESS))

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                fulfillments.findByOrderId(submitted.orderId).size shouldBeEqualTo 2
                fulfillmentLines.findByOrderId(submitted.orderId).size shouldBeEqualTo 3
            }
        }

        val (singleUnitLine, splitLine, shippedGroupId) =
            readState {
                val orderLines = lines.findByOrderId(submitted.orderId)
                val singleUnitLine = orderLines.single { it.sku == "sku-a" }
                val splitLine = orderLines.single { it.sku == "sku-b" }
                fulfillmentLines.findByLineId(splitLine.lineId).size shouldBeEqualTo 2
                fulfillmentLines.findByLineId(splitLine.lineId).sumOf { it.quantity } shouldBeEqualTo 2
                val shippedGroupId =
                    fulfillmentLines
                        .findByLineId(singleUnitLine.lineId)
                        .single()
                        .fulfillmentGroupId
                Triple(singleUnitLine, splitLine, shippedGroupId)
            }
        commands.advanceFulfillment(shippedGroupId, FulfillmentStatus.ALLOCATED)
        commands.advanceFulfillment(shippedGroupId, FulfillmentStatus.PICKING)
        commands.advanceFulfillment(shippedGroupId, FulfillmentStatus.SHIPPED)

        assertFailsWith<IllegalStateException> {
            commands.cancelUnshippedLine(submitted.orderId, singleUnitLine.lineId, 1, "CUSTOMER_REQUEST")
        }

        val cancellation =
            commands.cancelUnshippedLine(
                submitted.orderId,
                splitLine.lineId,
                1,
                "CUSTOMER_REQUEST"
            )

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                cancellations.findById(cancellation.cancellationCaseId).status shouldBeEqualTo
                    io.bluetape4k.workshop.commerce.order.domain.CancellationStatus.APPROVED
                refunds.findById(cancellation.refundCaseId).status shouldBeEqualTo RefundStatus.SUCCEEDED
                lines.findByLineId(splitLine.lineId)!!.cancelledQuantity shouldBeEqualTo 1
                val splitLinks = fulfillmentLines.findByLineId(splitLine.lineId)
                splitLinks.size shouldBeEqualTo 2
                splitLinks.sumOf { it.quantity } shouldBeEqualTo 1
                splitLinks.single { it.fulfillmentGroupId == shippedGroupId }.quantity shouldBeEqualTo 1
                splitLinks.single { it.fulfillmentGroupId != shippedGroupId }.quantity shouldBeEqualTo 0
                fulfillments.findById(shippedGroupId).status shouldBeEqualTo FulfillmentStatus.SHIPPED
                fulfillments
                    .findById(splitLinks.single { it.fulfillmentGroupId != shippedGroupId }.fulfillmentGroupId)
                    .status shouldBeEqualTo FulfillmentStatus.CANCELLED
                audits
                    .findByOrderId(submitted.orderId)
                    .count { it.aggregateType == AggregateType.REFUND_CASE } shouldBeEqualTo 3
                audits
                    .findByOrderId(submitted.orderId)
                    .count { it.aggregateType == AggregateType.CANCELLATION_CASE } shouldBeEqualTo 2
            }
        }

        commands.advanceFulfillment(shippedGroupId, FulfillmentStatus.DELIVERED)
        readState {
            orders.findById(submitted.orderId).status shouldBeEqualTo OrderStatus.COMPLETED
        }
    }

    @Test
    fun `all cancelled fulfillment groups cancel the order`() {
        val submitted =
            commands.submit(
                SubmitOrder(
                    tenantId = "tenant-a",
                    customerReference = "all-cancelled-order",
                    providerMode = ProviderMode.SUCCESS,
                    lines = listOf(SubmitOrderLine("sku-only", 1, BigDecimal("10.00")))
                )
            )

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                fulfillments.findByOrderId(submitted.orderId).size shouldBeEqualTo 1
                fulfillmentLines.findByOrderId(submitted.orderId).size shouldBeEqualTo 1
            }
        }
        val lineId = readState { lines.findByOrderId(submitted.orderId).single().lineId }

        commands.cancelUnshippedLine(submitted.orderId, lineId, 1, "CUSTOMER_REQUEST")

        await atMost Duration.ofSeconds(10) untilAsserted {
            readState {
                orders.findById(submitted.orderId).status shouldBeEqualTo OrderStatus.CANCELLED
                fulfillments.findByOrderId(submitted.orderId).single().status shouldBeEqualTo
                    FulfillmentStatus.CANCELLED
            }
        }
    }

    private fun <T : Any> readState(block: () -> T): T = requireNotNull(transactions.execute { block() })

    private fun validOrder(mode: ProviderMode) =
        SubmitOrder(
            tenantId = "tenant-a",
            customerReference = "customer-ref-${mode.name.lowercase()}",
            providerMode = mode,
            lines =
                listOf(
                    SubmitOrderLine("sku-a", 1, BigDecimal("10.00")),
                    SubmitOrderLine("sku-b", 2, BigDecimal("20.00"))
                )
        )
}
