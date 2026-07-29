package io.bluetape4k.workshop.spring.modulith.ddd.audit.fulfillment

import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.OrderApproved
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * commit 이후 fulfillment capacity 를 예약해 승인된 주문을 처리합니다.
 *
 * ## 동작 / 계약
 * - `@ApplicationModuleListener` 는 command transaction 이 commit 된 뒤 실행됩니다.
 * - 결정적 failure switch 로 테스트에서 실패한 publication replay 를 재현할 수 있습니다.
 * - [FulfillmentReservation.orderId] 가 unique 이므로 replay 된 event 는 멱등입니다.
 */
@Component
class FulfillmentReservationHandler(
    private val reservations: FulfillmentReservationRepository,
    private val failureSwitch: FulfillmentFailureSwitch,
) {

    /**
     * 승인된 주문에 대한 fulfillment 예약을 생성합니다.
     */
    @ApplicationModuleListener
    fun on(event: OrderApproved) {
        val orderId = event.aggregateId
        if (failureSwitch.failNext(orderId)) {
            throw IllegalStateException("fulfillment failed for orderId=$orderId")
        }
        if (!reservations.existsById(orderId)) {
            reservations.save(FulfillmentReservation.forOrder(orderId))
        }
    }
}

/**
 * listener retry 동작을 결정적으로 만들기 위해 테스트에서 사용하는 failure switch 입니다.
 */
@Component
class FulfillmentFailureSwitch {
    private val orderIdsToFail = ConcurrentHashMap.newKeySet<String>()

    /**
     * [orderId] 에 대한 다음 fulfillment 처리 시도를 실패시킵니다.
     */
    fun failOnce(orderId: String) {
        orderIdsToFail += orderId
    }

    /**
     * 설정된 [orderId] 에 대해 한 번만 true 를 반환합니다.
     */
    fun failNext(orderId: String): Boolean =
        orderIdsToFail.remove(orderId)

    /**
     * 설정된 모든 실패 상태를 지웁니다.
     */
    fun clear() {
        orderIdsToFail.clear()
    }
}
