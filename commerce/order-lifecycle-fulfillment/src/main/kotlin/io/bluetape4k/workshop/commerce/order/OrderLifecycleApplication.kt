package io.bluetape4k.workshop.commerce.order

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@EnableAsync
@SpringBootApplication
internal class OrderLifecycleApplication {
    companion object : KLogging()
}

fun main(args: Array<String>) {
    runApplication<OrderLifecycleApplication>(*args)
    OrderLifecycleApplication.log.info { "order_lifecycle_application_started" }
}
