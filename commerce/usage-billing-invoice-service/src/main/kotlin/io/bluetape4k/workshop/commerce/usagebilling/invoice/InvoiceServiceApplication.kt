package io.bluetape4k.workshop.commerce.usagebilling.invoice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class InvoiceServiceApplication

@Suppress("SpreadOperator") // Spring Boot's entry point is a vararg API.
fun main(args: Array<String>) {
    runApplication<InvoiceServiceApplication>(*args)
}
