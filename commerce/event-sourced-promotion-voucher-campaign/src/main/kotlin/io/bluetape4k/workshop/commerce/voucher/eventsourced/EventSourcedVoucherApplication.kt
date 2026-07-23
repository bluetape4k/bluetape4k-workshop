package io.bluetape4k.workshop.commerce.voucher.eventsourced

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
internal class EventSourcedVoucherApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // Spring Boot's entry point accepts Java varargs.
    runApplication<EventSourcedVoucherApplication>(*args)
}
