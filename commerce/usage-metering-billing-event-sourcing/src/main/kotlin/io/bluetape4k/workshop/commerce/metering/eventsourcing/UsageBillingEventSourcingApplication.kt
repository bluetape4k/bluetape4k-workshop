package io.bluetape4k.workshop.commerce.metering.eventsourcing

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class UsageBillingEventSourcingApplication

@Suppress("SpreadOperator") // Spring Boot's idiomatic Kotlin entry point requires varargs.
fun main(args: Array<String>) {
    runApplication<UsageBillingEventSourcingApplication>(*args)
}
