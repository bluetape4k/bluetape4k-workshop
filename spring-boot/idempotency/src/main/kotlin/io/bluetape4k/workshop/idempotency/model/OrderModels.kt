package io.bluetape4k.workshop.idempotency.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/**
 * 주문 생성을 위한 request payload 입니다.
 *
 * ## 동작 / 계약
 * - [productId] 는 비어 있지 않아야 합니다.
 * - [quantity] 는 양수여야 합니다.
 * - [userId] 는 비어 있지 않아야 합니다.
 */
data class OrderRequest(
    val productId: String,
    val quantity: Int,
    val userId: String,
) : Serializable {
    init {
        productId.requireNotBlank("productId")
        quantity.requirePositiveNumber("quantity")
        userId.requireNotBlank("userId")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 성공적으로 처리되었거나 replay 된 주문에 대해 반환하는 response 입니다.
 *
 * ## 동작 / 계약
 * - 최초 생성은 HTTP 201 을 반환합니다.
 * - 같은 Idempotency-Key 로 replay 된 요청은 같은 body 와 함께 HTTP 200 을 반환합니다.
 */
data class OrderResponse(
    val orderId: String,
    val status: String,
    val processedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * HTTP status 와 response body 를 함께 담아 idempotency cache 에 저장하는 envelope 입니다.
 *
 * ## 동작 / 계약
 * - [httpStatus] 는 최초 생성 시 반환한 status code 입니다(예: 201).
 * - [response] 는 원래 [OrderResponse] payload 입니다.
 */
data class CachedResponse(
    val httpStatus: Int,
    val response: OrderResponse,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
