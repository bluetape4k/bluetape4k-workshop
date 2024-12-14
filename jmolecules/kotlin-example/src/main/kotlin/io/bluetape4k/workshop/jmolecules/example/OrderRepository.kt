package io.bluetape4k.workshop.jmolecules.example

import org.springframework.data.repository.CrudRepository

interface OrderRepository: CrudRepository<Order, Order.OrderIdentifier> 
