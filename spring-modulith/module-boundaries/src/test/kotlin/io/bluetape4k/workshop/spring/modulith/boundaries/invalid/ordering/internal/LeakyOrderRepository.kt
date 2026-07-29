package io.bluetape4k.workshop.spring.modulith.boundaries.invalid.ordering.internal

/**
 * downstream module 이 import 하면 안 되는 test-only internal type 입니다.
 */
class LeakyOrderRepository {
    fun exists(orderId: String): Boolean =
        orderId.isNotBlank()
}
