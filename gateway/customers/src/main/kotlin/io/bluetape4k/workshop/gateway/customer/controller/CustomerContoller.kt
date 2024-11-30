package io.bluetape4k.workshop.gateway.customer.controller

import io.bluetape4k.workshop.gateway.customer.model.Customer
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/customers")
@CrossOrigin
class CustomerContoller {

    @GetMapping
    suspend fun getAll(): List<Customer> {
        return listOf(
            Customer("Winter"),
            Customer("Spring")
        )
    }
}
