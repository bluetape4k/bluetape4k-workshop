package io.bluetape4k.workshop.exposed.mvc.jdbc.order.controller

import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.ProductDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/products")
class ProductController(private val orderService: OrderService) {

    @GetMapping
    fun findAll(): List<ProductDTO> = orderService.findAllProducts()

    @GetMapping("/{id}")
    fun findById(@PathVariable id: Long): ProductDTO = orderService.findProductById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateProductRequest): ProductDTO =
        orderService.createProduct(req)
}
