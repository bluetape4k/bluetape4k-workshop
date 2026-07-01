package io.bluetape4k.workshop.spring.modulith.ddd.audit.fulfillment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Fulfillment reservation created after an order is approved.
 *
 * ## Behavior / Contract
 * - [orderId] is the primary key so publication replay cannot create duplicates.
 * - The row is a listener side effect and is created only after the order transaction commits.
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
         * Creates a reservation for [orderId].
         */
        fun forOrder(orderId: String): FulfillmentReservation =
            FulfillmentReservation(orderId = orderId)
    }
}
