package io.bluetape4k.workshop.commerce.usagebilling.billing

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BillingServiceApplication

@Suppress("SpreadOperator") // Spring Boot's entry point is a vararg API.
fun main(args: Array<String>) {
    runApplication<BillingServiceApplication>(*args)
}
