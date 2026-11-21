package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.order.AbstractOrderLifecycleIntegrationTest
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.domain.CancellationStatus
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentProviderEvent
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition
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
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OrderLifecycleIntegrationTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OrderLifecycleIntegrationTest(
/**
 * commands은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val commands: OrderCommandService,
/**
 * paymentEvents은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val paymentEvents: PaymentEventService,
/**
 * orders은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val orders: OrderRepository,
/**
 * payments은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val payments: PaymentAttemptRepository,
/**
 * providerEvents은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val providerEvents: ProviderEventInboxRepository,
/**
 * reservations은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reservations: InventoryReservationRepository,
/**
 * fulfillments은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fulfillments: FulfillmentGroupRepository,
/**
 * fulfillmentLines은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fulfillmentLines: FulfillmentLineRepository,
/**
 * cancellations은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val cancellations: CancellationCaseRepository,
/**
 * lines은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val lines: OrderLineRepository,
/**
 * refunds은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val refunds: RefundCaseRepository,
/**
 * audits은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val audits: LifecycleAuditRepository,
/**
 * failureSwitch은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val failureSwitch: InventoryListenerFailureSwitch,
/**
 * reconciliation은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reconciliation: PublicationReconciliationService,
/**
 * publicationRepository은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val publicationRepository: EventPublicationRepository,
    transactionManager: PlatformTransactionManager,
): AbstractOrderLifecycleIntegrationTest() {

    companion object: KLogging()

/**
 * transactions은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val transactions = TransactionTemplate(transactionManager)

    @AfterEach
    fun clearFailure() = failureSwitch.clear()

    @Test
/**
 * 이 시나리오는 "payment success commits inventory and creates split fulfillment without completing
 * order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `payment success commits inventory and creates split fulfillment without completing order`() {
        AopUtils.getTargetClass(publicationRepository).name shouldBeEqualTo
                "io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository"

        val submitted = commands.submit(validOrder(ProviderMode.SUCCESS))

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
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
/**
 * 이 시나리오는 "duplicate and out of order provider events do not reapply terminal payment" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `duplicate and out of order provider events do not reapply terminal payment`() {
        val submitted = commands.submit(validOrder(ProviderMode.OUT_OF_ORDER))

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
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
/**
 * 이 시나리오는 "conflicting provider payload remains visible as unresolved evidence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `conflicting provider payload remains visible as unresolved evidence`() {
        val submitted = commands.submit(validOrder(ProviderMode.DELAYED_SUCCESS))
        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
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
        ) shouldBeEqualTo ProviderEventDisposition.APPLIED
        paymentEvents.ingest(
            PaymentProviderEvent(
                providerEventId,
                submitted.paymentAttemptId,
                ProviderEventKind.FAILED,
                Instant.parse("2026-07-18T00:00:11Z")
            )
        ) shouldBeEqualTo ProviderEventDisposition.CONFLICT

        readState {
            providerEvents.countUnresolved() shouldBeEqualTo 1L
            providerEvents.find("FAKE", providerEventId)!!.disposition shouldBeEqualTo
                    ProviderEventDisposition.CONFLICT
        }
    }

    @Test
/**
 * 이 시나리오는 "failed publication remains visible and replay completes inventory exactly once" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `failed publication remains visible and replay completes inventory exactly once`() {
        val submitted = commands.submit(validOrder(ProviderMode.DELAYED_SUCCESS))
        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
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

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
            readState {
                publicationRepository.countByStatus(EventPublication.Status.FAILED) shouldBeGreaterThan 0
                reservations.findByOrderId(submitted.orderId)!!.status shouldBeEqualTo ReservationStatus.HELD
            }
        }

        reconciliation.replayFailed(1)

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
            readState {
                reservations.findByOrderId(submitted.orderId)!!.status shouldBeEqualTo ReservationStatus.COMMITTED
                fulfillments.findByOrderId(submitted.orderId).size shouldBeEqualTo 2
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "delayed payment success is reconciled through a deterministic provider event" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `delayed payment success is reconciled through a deterministic provider event`() {
        val submitted = commands.submit(validOrder(ProviderMode.DELAYED_SUCCESS))

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
            readState {
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.AUTHORIZING
            }
        }

        paymentEvents.reconcileDelayedSuccess(submitted.paymentAttemptId) shouldBeEqualTo ProviderEventDisposition.APPLIED

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
            readState {
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.SUCCEEDED
                fulfillments.findByOrderId(submitted.orderId).size shouldBeEqualTo 2
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "one line split across shipped and unshipped groups can cancel only its remaining
 * quantity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `one line split across shipped and unshipped groups can cancel only its remaining quantity`() {
        val submitted = commands.submit(validOrder(ProviderMode.SUCCESS))

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
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
                val shippedGroupId = fulfillmentLines
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

        val cancellation = commands.cancelUnshippedLine(
            submitted.orderId,
            splitLine.lineId,
            1,
            "CUSTOMER_REQUEST"
        )

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
            readState {
                cancellations.findById(cancellation.cancellationCaseId).status shouldBeEqualTo CancellationStatus.APPROVED
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
/**
 * 이 시나리오는 "all cancelled fulfillment groups cancel the order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
            readState {
                fulfillments.findByOrderId(submitted.orderId).size shouldBeEqualTo 1
                fulfillmentLines.findByOrderId(submitted.orderId).size shouldBeEqualTo 1
            }
        }
        val lineId = readState { lines.findByOrderId(submitted.orderId).single().lineId }

        commands.cancelUnshippedLine(submitted.orderId, lineId, 1, "CUSTOMER_REQUEST")

        await atMost 10.seconds withPollInterval 500.milliseconds untilAsserted {
            readState {
                orders.findById(submitted.orderId).status shouldBeEqualTo OrderStatus.CANCELLED
                fulfillments.findByOrderId(submitted.orderId)
                    .single().status shouldBeEqualTo FulfillmentStatus.CANCELLED
            }
        }
    }

/**
 * readState은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T: Any> readState(
        crossinline block: () -> T,
    ): T = transactions.execute { block() }.shouldNotBeNull()

/**
 * validOrder은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validOrder(mode: ProviderMode): SubmitOrder =
        SubmitOrder(
            tenantId = "tenant-a",
            customerReference = "customer-ref-${mode.name.lowercase()}",
            providerMode = mode,
            lines = listOf(
                SubmitOrderLine("sku-a", 1, BigDecimal("10.00")),
                SubmitOrderLine("sku-b", 2, BigDecimal("20.00"))
            )
        )
}
