package io.bluetape4k.workshop.exposed.mvc.jdbc.order.exception

class InsufficientStockException(val productId: Long) :
    RuntimeException("Insufficient stock for product $productId")
