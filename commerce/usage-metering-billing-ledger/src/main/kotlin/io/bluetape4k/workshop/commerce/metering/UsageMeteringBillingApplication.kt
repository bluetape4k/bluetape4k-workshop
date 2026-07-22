package io.bluetape4k.workshop.commerce.metering

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class UsageMeteringBillingApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // Spring Boot's entry point accepts Java varargs.
    runApplication<UsageMeteringBillingApplication>(*args)
}
