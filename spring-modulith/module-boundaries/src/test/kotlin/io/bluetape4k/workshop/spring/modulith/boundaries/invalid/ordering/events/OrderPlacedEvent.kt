package io.bluetape4k.workshop.spring.modulith.boundaries.invalid.ordering.events

import java.io.Serializable

/**
 * invalid ordering fixture 가 export 하는 test-only event contract 입니다.
 */
data class OrderPlacedEvent(
    val orderId: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
