package io.bluetape4k.workshop.spring.modulith.boundaries.ordering.internal

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic order-number generator for tests and workshop demos.
 */
@Component
class OrderNumberGenerator {

    private val sequence = AtomicLong(1_000)

    fun nextOrderId(): String =
        "order-${sequence.getAndIncrement()}"
}
