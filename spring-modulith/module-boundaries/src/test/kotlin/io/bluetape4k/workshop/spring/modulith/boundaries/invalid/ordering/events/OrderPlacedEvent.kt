package io.bluetape4k.workshop.spring.modulith.boundaries.invalid.ordering.events

import java.io.Serializable

/**
 * Test-only event contract exported by the invalid ordering fixture.
 */
data class OrderPlacedEvent(
    val orderId: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
