package io.bluetape4k.workshop.commerce.metering.eventsourcing

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class UsageBillingEventSourcingApplication

fun main(args: Array<String>) {
    runApplication<UsageBillingEventSourcingApplication>(*args)
}
