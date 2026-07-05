package io.bluetape4k.workshop.gateway.customer.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.gateway.customer.model.Customer
import io.bluetape4k.workshop.gateway.customer.service.CustomerService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController(
    private val customerService: CustomerService,
) {

    companion object: KLoggingChannel()

    @GetMapping
    suspend fun getAll(): List<Customer> {
        return customerService.getAll()
    }
}
