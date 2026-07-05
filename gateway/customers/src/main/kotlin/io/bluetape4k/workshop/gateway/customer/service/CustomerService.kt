package io.bluetape4k.workshop.gateway.customer.service

import io.bluetape4k.workshop.gateway.customer.model.Customer
import org.springframework.stereotype.Service

@Service
class CustomerService {

    suspend fun getAll(): List<Customer> {
        return listOf(
            Customer("Winter"),
            Customer("Spring"),
        )
    }
}
