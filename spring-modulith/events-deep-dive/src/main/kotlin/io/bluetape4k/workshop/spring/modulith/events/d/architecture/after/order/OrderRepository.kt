package io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.order

import org.springframework.data.repository.CrudRepository

interface OrderRepository: CrudRepository<Order, String> 
