package io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.order

import org.springframework.data.repository.CrudRepository

interface OrderRepository: CrudRepository<Order, String> 
