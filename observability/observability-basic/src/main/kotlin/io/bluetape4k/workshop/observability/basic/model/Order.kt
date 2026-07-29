package io.bluetape4k.workshop.observability.basic.model

import java.io.Serializable

/**
 * resolved inventory availability 를 포함한 placed order 를 표현합니다.
 *
 * ## Behavior / Contract
 * - `inventoryAvailable` 은 downstream inventory service 에서 조회한 count 를 반영합니다.
 * - `OrderService.getOrder` 가 반환하는 read-only view model 입니다.
 */
data class Order(
    val id: Long,
    val itemId: Long,
    val quantity: Int,
    val inventoryAvailable: Int,
) : Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
