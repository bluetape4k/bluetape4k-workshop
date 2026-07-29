package io.bluetape4k.workshop.commerce.usagebilling.usage

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class UsageServiceApplication

@Suppress("SpreadOperator") // Spring Boot's entry point is a vararg API.
fun main(args: Array<String>) {
    runApplication<UsageServiceApplication>(*args)
}
