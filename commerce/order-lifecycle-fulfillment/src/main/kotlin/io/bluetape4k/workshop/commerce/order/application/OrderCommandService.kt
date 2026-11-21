package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.domain.CancellationPolicy
import io.bluetape4k.workshop.commerce.order.domain.CancellationStatus
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentPolicy
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderPolicy
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderSubmitted
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.RefundRequested
import io.bluetape4k.workshop.commerce.order.domain.RefundStatus
import io.bluetape4k.workshop.commerce.order.domain.ReservationStatus
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrder
import io.bluetape4k.workshop.commerce.order.persistence.CancellationCaseRecord
import io.bluetape4k.workshop.commerce.order.persistence.CancellationCaseRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRecord
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderLineRecord
import io.bluetape4k.workshop.commerce.order.persistence.OrderLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderRecord
import io.bluetape4k.workshop.commerce.order.persistence.OrderRepository
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRecord
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRepository
import io.bluetape4k.workshop.commerce.order.persistence.RefundCaseRecord
import io.bluetape4k.workshop.commerce.order.persistence.RefundCaseRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.util.*
/**
 * SubmittedOrderIds은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property orderId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property paymentAttemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class SubmittedOrderIds(
    /**
     * `orderId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId: UUID,
    /**
     * `paymentAttemptId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val paymentAttemptId: UUID,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * LineCancellationResult은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property cancellationCaseId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property refundCaseId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class LineCancellationResult(
    /**
     * `cancellationCaseId`은 주문·결제·재고·배송에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val cancellationCaseId: UUID,
    /**
     * `refundCaseId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val refundCaseId: UUID,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * OrderCommandService은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property orders 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property payments 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property lines 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reservations 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property fulfillments 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property fulfillmentLines 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property cancellations 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property refunds 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audit 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property events 주문·fulfillment 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property clock 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
internal class OrderCommandService(
    /**
     * `orders`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val orders: OrderRepository,
    /**
     * `payments`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val payments: PaymentAttemptRepository,
    /**
     * `lines`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val lines: OrderLineRepository,
    /**
     * `reservations`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val reservations: InventoryReservationRepository,
    /**
     * `fulfillments`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val fulfillments: FulfillmentGroupRepository,
    /**
     * `fulfillmentLines`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val fulfillmentLines: FulfillmentLineRepository,
    /**
     * `cancellations`은 주문·결제·재고·배송에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    private val cancellations: CancellationCaseRepository,
    /**
     * `refunds`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val refunds: RefundCaseRepository,
    /**
     * `audit`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audit: LifecycleAuditAppender,
    /**
     * `events`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val events: ApplicationEventPublisher,
    /**
     * `clock`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    companion object: KLogging() {
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,120}")
        /**
         * 주문·fulfillment의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val REASON = Regex("[A-Z0-9_]{1,80}")
        /**
         * 주문·fulfillment의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val NON_CANCELLABLE_FULFILLMENT_STATUSES = setOf(
            FulfillmentStatus.SHIPPED,
            FulfillmentStatus.DELIVERED,
            FulfillmentStatus.CANCELLED
        )
        /**
         * 주문·fulfillment의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val ORDER_TERMINAL_FULFILLMENT_STATUSES = setOf(
            FulfillmentStatus.DELIVERED,
            FulfillmentStatus.CANCELLED
        )
    }

    /**
     * `submit`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = submit(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun submit(command: SubmitOrder): SubmittedOrderIds {
        require(command.tenantId.matches(IDENTIFIER)) { "invalid tenantId" }
        require(command.customerReference.matches(IDENTIFIER)) { "invalid customerReference" }
        command.lines.requireNotEmpty("command.lines")
        command.lines.size.requireInRange(1, 50, "command.lines.size")
        command.lines.forEach {
            require(it.sku.matches(IDENTIFIER))
            it.quantity.requireInRange(1, 1_000, "quantity")
            require(it.unitPrice.signum() >= 0)
        }

        val orderId = Uuid.V7.nextId()
        val paymentId = Uuid.V7.nextId()
        val reservationId = Uuid.V7.nextId()
        val orderRecord = OrderRecord(
            id = orderId,
            tenantId = command.tenantId,
            customerReference = command.customerReference,
            status = OrderStatus.SUBMITTED,
            providerMode = command.providerMode
        )
        orders.save(orderRecord)
        payments.save(PaymentAttemptRecord(paymentId, orderId, PaymentStatus.CREATED))
        reservations.save(InventoryReservationRecord(reservationId, orderId, ReservationStatus.HELD))

        command.lines.forEach { line ->
            val orderLineRecord = OrderLineRecord(
                lineId = Uuid.V7.nextId(),
                orderId = orderId,
                sku = line.sku,
                quantity = line.quantity,
                unitPrice = line.unitPrice
            )
            lines.save(orderLineRecord)
        }
        audit.append(orderId, AggregateType.ORDER, orderId, 0, null, OrderStatus.SUBMITTED, actor = "CUSTOMER")
        audit.append(
            orderId,
            AggregateType.PAYMENT_ATTEMPT,
            paymentId,
            0,
            null,
            PaymentStatus.CREATED,
            actor = "SYSTEM"
        )
        audit.append(
            orderId,
            AggregateType.INVENTORY_RESERVATION,
            reservationId,
            0,
            null,
            ReservationStatus.HELD,
            actor = "SYSTEM"
        )
        events.publishEvent(
            OrderSubmitted(Uuid.V7.nextId(), orderId, paymentId, command.providerMode, Instant.now(clock))
        )
        log.info {
            "order_submitted orderId=$orderId paymentAttemptId=$paymentId providerMode=${command.providerMode} " +
                    "lineCount=${command.lines.size}"
        }
        return SubmittedOrderIds(orderId, paymentId)
    }

    /**
     * `advanceFulfillment`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = advanceFulfillment(groupId = UUID.randomUUID(), target = target)
     * ```
     * @param groupId 요청 경계에서 검증된 `groupId`입니다.
     * @param target 요청 경계에서 검증된 `target`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun advanceFulfillment(
        groupId: UUID,
        target: FulfillmentStatus,
    ): Boolean {
        val group = fulfillments.findById(groupId)
        FulfillmentPolicy.transition(group.status, target)
        val updated = fulfillments.transition(group.id, group.revision, group.status, target)
        if (updated) {
            audit.append(
                group.orderId,
                AggregateType.FULFILLMENT_GROUP,
                group.id,
                group.revision + 1,
                group.status,
                target,
                actor = "OPERATOR"
            )
            finishOrderWhenFulfillmentTerminal(group.orderId)
            log.info {
                "fulfillment_transition orderId=${group.orderId} groupId=$groupId from=${group.status} to=$target " +
                        "revision=${group.revision + 1}"
            }
        } else {
            log.warn {
                "fulfillment_transition_conflict orderId=${group.orderId} groupId=$groupId " +
                        "expectedRevision=${group.revision} from=${group.status} to=$target"
            }
        }
        return updated
    }

    /**
     * `cancelUnshippedLine`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = cancelUnshippedLine(orderId = UUID.randomUUID(), lineId = UUID.randomUUID(),
     * quantity
     * = 25, reasonCode = "demo")
     * ```
     * @param orderId 요청 경계에서 검증된 `orderId`입니다.
     * @param lineId 요청 경계에서 검증된 `lineId`입니다.
     * @param quantity 요청 경계에서 검증된 `quantity`입니다.
     * @param reasonCode 요청 경계에서 검증된 `reasonCode`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun cancelUnshippedLine(
        orderId: UUID,
        lineId: UUID,
        quantity: Int,
        reasonCode: String,
    ): LineCancellationResult {
        require(reasonCode.matches(REASON)) { "invalid reasonCode" }
        quantity.requirePositiveNumber("quantity")
        val line = lines.findByLineId(lineId)
        check(line != null && line.orderId == orderId) { "order line not found" }
        val linkedLines = fulfillmentLines.findByLineId(lineId)
        val linkedGroups = linkedLines.map { fulfillments.findById(it.fulfillmentGroupId) }

        val cancellableGroupIds = linkedGroups
            .filter { it.status !in NON_CANCELLABLE_FULFILLMENT_STATUSES }
            .mapTo(linkedSetOf()) { it.id }
        val cancellableQuantity =
            linkedLines.filter { it.fulfillmentGroupId in cancellableGroupIds }.sumOf { it.quantity }
        check(cancellableQuantity >= quantity) { "requested quantity exceeds unshipped quantity" }

        val cancellationId = Uuid.V7.nextId()
        cancellations.save(
            CancellationCaseRecord(
                id = cancellationId,
                orderId = orderId,
                lineId = lineId,
                quantity = quantity,
                status = CancellationStatus.REQUESTED,
                reasonCode = reasonCode
            )
        )
        audit.append(
            orderId,
            AggregateType.CANCELLATION_CASE,
            cancellationId,
            0,
            null,
            CancellationStatus.REQUESTED,
            reasonCode,
            "OPERATOR"
        )
        check(lines.cancel(lineId, quantity)) { "line cancellation conflict" }
        check(fulfillmentLines.cancel(lineId, cancellableGroupIds, quantity)) {
            "fulfillment line cancellation conflict"
        }
        CancellationPolicy.transition(CancellationStatus.REQUESTED, CancellationStatus.APPROVED)
        check(
            cancellations.transition(
                cancellationId,
                0,
                CancellationStatus.REQUESTED,
                CancellationStatus.APPROVED
            )
        ) { "cancellation approval conflict" }

        audit.append(
            orderId,
            AggregateType.CANCELLATION_CASE,
            cancellationId,
            1,
            CancellationStatus.REQUESTED,
            CancellationStatus.APPROVED,
            reasonCode,
            "SYSTEM"
        )
        cancelEmptyFulfillmentGroups(orderId, linkedGroups.filter { it.id in cancellableGroupIds })
        finishOrderWhenFulfillmentTerminal(orderId)
        val refundId = Uuid.V7.nextId()
        refunds.save(RefundCaseRecord(refundId, orderId, RefundStatus.REQUESTED, reasonCode = reasonCode))

        audit.append(
            orderId,
            AggregateType.REFUND_CASE,
            refundId,
            0,
            null,
            RefundStatus.REQUESTED,
            reasonCode,
            "OPERATOR"
        )
        events.publishEvent(RefundRequested(Uuid.V7.nextId(), orderId, refundId, reasonCode, Instant.now(clock)))
        log.info {
            "line_cancellation_approved orderId=$orderId lineId=$lineId quantity=$quantity " +
                    "cancellationCaseId=$cancellationId refundCaseId=$refundId reasonCode=$reasonCode"
        }

        return LineCancellationResult(cancellationId, refundId)
    }

    /**
     * cancelEmptyFulfillmentGroups은 주문 라이프사이클과 fulfillment의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고,
     * 이미 처리된
     * 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param linkedGroups 업무 처리에 필요한 linkedGroups 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun cancelEmptyFulfillmentGroups(
        orderId: UUID,
        linkedGroups: List<io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRecord>,
    ) {
        val remainingByGroup = fulfillmentLines.findByOrderId(orderId).groupBy { it.fulfillmentGroupId }
        linkedGroups.forEach { group ->
            if (remainingByGroup[group.id].orEmpty().all { it.quantity == 0 }) {
                FulfillmentPolicy.transition(group.status, FulfillmentStatus.CANCELLED)
                if (fulfillments.transition(
                        group.id,
                        group.revision,
                        group.status,
                        FulfillmentStatus.CANCELLED,
                        "LINE_CANCELLED"
                    )
                ) {
                    audit.append(
                        orderId,
                        AggregateType.FULFILLMENT_GROUP,
                        group.id,
                        group.revision + 1,
                        group.status,
                        FulfillmentStatus.CANCELLED,
                        "LINE_CANCELLED",
                        "SYSTEM"
                    )
                }
            }
        }
    }

    /**
     * finishOrderWhenFulfillmentTerminal은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화
     * 결과가 서로
     * 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun finishOrderWhenFulfillmentTerminal(orderId: UUID) {
        val groups = fulfillments.findByOrderId(orderId)
        if (groups.isNotEmpty() && groups.all { it.status in ORDER_TERMINAL_FULFILLMENT_STATUSES }) {
            val order = orders.findById(orderId)
            val target = if (groups.all { it.status == FulfillmentStatus.CANCELLED }) {
                OrderStatus.CANCELLED
            } else {
                OrderStatus.COMPLETED
            }
            OrderPolicy.transition(order.status, target)
            if (orders.transition(order.id, order.revision, order.status, target)) {
                audit.append(
                    orderId,
                    AggregateType.ORDER,
                    orderId,
                    order.revision + 1,
                    order.status,
                    target,
                    actor = "SYSTEM"
                )
                log.info {
                    "order_terminal orderId=$orderId status=$target revision=${order.revision + 1} " +
                            "deliveredGroups=${groups.count { it.status == FulfillmentStatus.DELIVERED }} " +
                            "cancelledGroups=${groups.count { it.status == FulfillmentStatus.CANCELLED }}"
                }
            }
        }
    }

}
