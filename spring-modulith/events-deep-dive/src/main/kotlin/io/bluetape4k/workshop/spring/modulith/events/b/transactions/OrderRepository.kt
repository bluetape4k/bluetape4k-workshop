package io.bluetape4k.workshop.spring.modulith.events.b.transactions

import org.springframework.data.repository.CrudRepository

interface OrderRepository: CrudRepository<Order, String> 
