package io.bluetape4k.workshop.exposed.mvc.jdbc.order.exception

import io.bluetape4k.exceptions.BluetapeException

class InsufficientStockException(val productId: Long):
    BluetapeException("Insufficient stock for product $productId")
