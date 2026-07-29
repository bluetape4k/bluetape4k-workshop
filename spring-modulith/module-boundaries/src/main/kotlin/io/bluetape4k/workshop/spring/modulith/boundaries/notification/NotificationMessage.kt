package io.bluetape4k.workshop.spring.modulith.boundaries.notification

import java.io.Serializable

/**
 * order event 로부터 생성되는 notification module state 입니다.
 */
data class NotificationMessage(
    val orderId: String,
    val customerId: String,
    val message: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
