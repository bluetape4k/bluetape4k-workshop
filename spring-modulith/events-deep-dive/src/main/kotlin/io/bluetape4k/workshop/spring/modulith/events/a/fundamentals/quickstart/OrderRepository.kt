package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.quickstart

interface OrderRepository {

    fun save(order: Order): Order

}
