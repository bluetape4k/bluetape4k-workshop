package io.bluetape4k.workshop.spring.modulith.boundaries.invalid.ordering.internal

/**
 * Test-only internal type that downstream modules must not import.
 */
class LeakyOrderRepository {
    fun exists(orderId: String): Boolean =
        orderId.isNotBlank()
}
