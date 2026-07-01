package io.bluetape4k.workshop.spring.modulith.ddd.audit.fulfillment

import io.bluetape4k.workshop.spring.modulith.ddd.audit.orders.OrderApproved
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles approved orders by reserving fulfillment capacity after commit.
 *
 * ## Behavior / Contract
 * - `@ApplicationModuleListener` runs after the command transaction commits.
 * - A deterministic failure switch lets tests demonstrate failed publication replay.
 * - Replayed events are idempotent because [FulfillmentReservation.orderId] is unique.
 */
@Component
class FulfillmentReservationHandler(
    private val reservations: FulfillmentReservationRepository,
    private val failureSwitch: FulfillmentFailureSwitch,
) {

    /**
     * Creates a fulfillment reservation for an approved order.
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
 * Test-facing failure switch used to make listener retry behavior deterministic.
 */
@Component
class FulfillmentFailureSwitch {
    private val orderIdsToFail = ConcurrentHashMap.newKeySet<String>()

    /**
     * Fails the next fulfillment handling attempt for [orderId].
     */
    fun failOnce(orderId: String) {
        orderIdsToFail += orderId
    }

    /**
     * Returns true once for a configured [orderId].
     */
    fun failNext(orderId: String): Boolean =
        orderIdsToFail.remove(orderId)

    /**
     * Clears all configured failures.
     */
    fun clear() {
        orderIdsToFail.clear()
    }
}
