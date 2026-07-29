package io.bluetape4k.workshop.spring.modulith.boundaries.ordering.internal

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * 테스트와 workshop demo 를 위한 결정적 order-number generator 입니다.
 */
@Component
class OrderNumberGenerator {

    private val sequence = AtomicLong(1_000)

    fun nextOrderId(): String =
        "order-${sequence.getAndIncrement()}"
}
