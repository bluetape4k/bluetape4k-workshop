package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.springdata

import org.springframework.data.repository.CrudRepository

interface OrderRepository: CrudRepository<Order, String> 
