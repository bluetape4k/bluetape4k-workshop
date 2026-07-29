package io.bluetape4k.workshop.spring.modulith.ddd.audit.fulfillment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 주문 승인 후 생성되는 fulfillment 예약입니다.
 *
 * ## 동작 / 계약
 * - [orderId] 가 기본 키이므로 publication replay 가 중복 예약을 만들 수 없습니다.
 * - 이 row 는 listener side effect 이며, 주문 transaction 이 commit 된 뒤에만 생성됩니다.
 */
@Entity
@Table(name = "fulfillment_reservations")
class FulfillmentReservation(
    @Id
    @Column(name = "order_id", nullable = false, updatable = false, length = 96)
    var orderId: String = "",

    @Column(name = "reserved_at", nullable = false, updatable = false)
    var reservedAt: Instant = Instant.now(),
) {
    companion object {
        /**
         * [orderId] 에 대한 예약을 생성합니다.
         */
        fun forOrder(orderId: String): FulfillmentReservation =
            FulfillmentReservation(orderId = orderId)
    }
}
