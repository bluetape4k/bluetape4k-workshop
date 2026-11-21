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
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
/**
 * PaymentEventService은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property payments 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property inbox 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audit 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property events 주문·fulfillment 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property clock 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
internal class PaymentEventService(
    /**
     * `payments`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val payments: PaymentAttemptRepository,
    /**
     * `inbox`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val inbox: ProviderEventInboxRepository,
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
    companion object: KLogging()

    /**
     * `ingest`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = ingest(event = event)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun ingest(event: PaymentProviderEvent): ProviderEventDisposition {
        val payment = payments.findById(event.paymentAttemptId)
        val target = targetStatus(payment.status, event.kind)
        val proposed = if (target == null) {
            ProviderEventDisposition.IGNORED_OUT_OF_ORDER
        } else {
            ProviderEventDisposition.APPLIED
        }
        val fingerprint = IdempotencyFingerprint.request(
            "${event.paymentAttemptId}|${event.kind}|${event.occurredAt}"
        )
        val eventRecord = ProviderEventRecord(
            provider = "FAKE",
            providerEventId = event.providerEventId,
            paymentAttemptId = event.paymentAttemptId,
            payloadFingerprint = fingerprint,
            eventKind = event.kind,
            disposition = proposed,
            providerOccurredAt = event.occurredAt
        )
        val disposition = inbox.record(eventRecord)
        if (disposition != ProviderEventDisposition.APPLIED || target == null) {
            log.info {
                "provider_event_disposition paymentAttemptId=${event.paymentAttemptId} " +
                        "providerEventId=${event.providerEventId} kind=${event.kind} disposition=$disposition"
            }
            return disposition
        }

        PaymentPolicy.transition(payment.status, target)

        if (!payments.transition(
                id = payment.id,
                expectedRevision = payment.revision,
                from = payment.status,
                to = target,
                providerRef = event.providerEventId
            )) {
            inbox.updateDisposition("FAKE", event.providerEventId, ProviderEventDisposition.UNRESOLVED)
            log.warn {
                "provider_event_unresolved paymentAttemptId=${event.paymentAttemptId} " +
                        "providerEventId=${event.providerEventId} expectedRevision=${payment.revision}"
            }
            return ProviderEventDisposition.UNRESOLVED
        }
        audit.append(
            orderId = payment.orderId,
            aggregateType = AggregateType.PAYMENT_ATTEMPT,
            aggregateId = payment.id,
            revision = payment.revision + 1,
            from = payment.status,
            to = target,
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

    /**
     * `reconcileDelayedSuccess`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = reconcileDelayedSuccess(paymentAttemptId = UUID.randomUUID())
     * ```
     * @param paymentAttemptId 요청 경계에서 검증된 `paymentAttemptId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun reconcileDelayedSuccess(paymentAttemptId: UUID): ProviderEventDisposition {
        val payment = payments.findById(paymentAttemptId)
        check(payment.status == PaymentStatus.AUTHORIZING) { "payment is not awaiting delayed success" }
        val event = PaymentProviderEvent(
            providerEventId = "fake-$paymentAttemptId-success",
            paymentAttemptId = paymentAttemptId,
            kind = ProviderEventKind.SUCCEEDED,
            occurredAt = Instant.now(clock)
        )
        return ingest(event)
    }

    /**
     * targetStatus은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param current 업무 처리에 필요한 current 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun targetStatus(
        current: PaymentStatus,
        kind: ProviderEventKind,
    ): PaymentStatus? = when (current) {
        PaymentStatus.CREATED if kind == ProviderEventKind.AUTHORIZING   -> PaymentStatus.AUTHORIZING
        PaymentStatus.AUTHORIZING if kind == ProviderEventKind.SUCCEEDED -> PaymentStatus.SUCCEEDED
        PaymentStatus.AUTHORIZING if kind == ProviderEventKind.FAILED    -> PaymentStatus.FAILED
        else                                                             -> null
    }
}
/**
 * OrderSubmittedListener은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property provider 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property paymentEvents 주문·fulfillment 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property orderLifecycleExecutor 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
internal class OrderSubmittedListener(
    /**
     * `provider`은 주문·결제·재고·배송에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val provider: PaymentProvider,
    /**
     * `paymentEvents`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val paymentEvents: PaymentEventService,
    /**
     * `orderLifecycleExecutor`은 주문·결제·재고·배송에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val orderLifecycleExecutor: ExecutorService,
) {
    /**
     * `on`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = on(event = event)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ApplicationModuleListener
    fun on(event: OrderSubmitted) {
        val providerEvents = orderLifecycleExecutor.submit<List<PaymentProviderEvent>> {
            provider.authorize(event)
        }.get()

        log.info {
            "payment_authorization_completed orderId=${event.orderId} paymentAttemptId=${event.paymentAttemptId} " +
                    "providerMode=${event.providerMode} eventCount=${providerEvents.size}"
        }
        providerEvents.forEach(paymentEvents::ingest)
    }

    companion object: KLogging()
}
/**
 * PaymentSucceededListener은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property orders 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reservations 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audit 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property events 주문·fulfillment 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property clock 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property failureSwitch 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
internal class PaymentSucceededListener(
    /**
     * `orders`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val orders: OrderRepository,
    /**
     * `reservations`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val reservations: InventoryReservationRepository,
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
    /**
     * `failureSwitch`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val failureSwitch: InventoryListenerFailureSwitch,
) {

    companion object: KLogging()

    /**
     * `on`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = on(event = event)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ApplicationModuleListener
    fun on(event: PaymentSucceeded) {
        if (failureSwitch.failNext(event.orderId)) {
            log.warn { "inventory_listener_injected_failure orderId=${event.orderId}" }
            error("inventory listener failed for orderId=${event.orderId}")
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
                    id = reservation.id,
                    expectedRevision = reservation.revision,
                    from = reservation.status,
                    to = ReservationStatus.COMMITTED
                )
            ) {
                audit.append(
                    orderId = event.orderId,
                    aggregateType = AggregateType.INVENTORY_RESERVATION,
                    aggregateId = reservation.id,
                    revision = reservation.revision + 1,
                    from = reservation.status,
                    to = ReservationStatus.COMMITTED,
                    actor = "SYSTEM"
                )
                val committed = InventoryCommitted(Uuid.V7.nextId(), event.orderId, reservation.id, Instant.now(clock))
                events.publishEvent(committed)

                log.info {
                    "inventory_committed orderId=${event.orderId} reservationId=${reservation.id} " +
                            "revision=${reservation.revision + 1}"
                }
            }
        }
    }
}

@Component
/**
 * InventoryListenerFailureSwitch은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class InventoryListenerFailureSwitch {

    companion object: KLogging()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val orderIds = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * `failOnce`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = failOnce(orderId = UUID.randomUUID())
     * ```
     * @param orderId 요청 경계에서 검증된 `orderId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun failOnce(orderId: UUID) {
        orderIds += orderId
        log.debug { "inventory_listener_failure_armed orderId=$orderId" }
    }

    /**
     * `failNext`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = failNext(orderId = UUID.randomUUID())
     * ```
     * @param orderId 요청 경계에서 검증된 `orderId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun failNext(orderId: UUID): Boolean = orderIds.remove(orderId)

    /**
     * `clear`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = clear()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun clear() = orderIds.clear()

}
/**
 * InventoryCommittedListener은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property orders 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property lines 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property fulfillments 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property fulfillmentLines 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audit 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
internal class InventoryCommittedListener(
    /**
     * `orders`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val orders: OrderRepository,
    /**
     * `lines`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val lines: OrderLineRepository,
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
     * `audit`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audit: LifecycleAuditAppender,
) {
    companion object: KLogging()

    /**
     * `on`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = on(event = event)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ApplicationModuleListener
    fun on(event: InventoryCommitted) {
        if (fulfillments.findByOrderId(event.orderId).isEmpty()) {
            val orderLines = lines.findByOrderId(event.orderId)
            val groupCount = minOf(2, orderLines.sumOf { it.quantity })
            val groups = List(groupCount) { index ->
                val group = fulfillments.save(
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
                        "orderLineCount=${orderLines.size} fulfillmentLinkCount=${fulfillmentLines.findByOrderId(event.orderId).size}"
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
}
/**
 * RefundRequestedListener은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property refunds 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audit 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
internal class RefundRequestedListener(
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
) {
    companion object: KLogging()

    /**
     * `on`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = on(event = event)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ApplicationModuleListener
    fun on(event: RefundRequested) {
        var refund = refunds.findById(event.refundCaseId)
        if (refund.status == RefundStatus.REQUESTED) {
            RefundPolicy.transition(refund.status, RefundStatus.PENDING_PROVIDER)
            if (refunds.transition(refund.id, refund.revision, refund.status, RefundStatus.PENDING_PROVIDER)) {
                audit.append(
                    orderId = refund.orderId,
                    aggregateType = AggregateType.REFUND_CASE,
                    aggregateId = refund.id,
                    revision = refund.revision + 1,
                    from = refund.status,
                    to = RefundStatus.PENDING_PROVIDER,
                    reason = event.reasonCode,
                    actor = "SYSTEM"
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
}
