package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** Boots the Java 25 Spring MVC concert ticket flash-sale example. */
@SpringBootApplication
@ConfigurationPropertiesScan
class TicketFlashSaleApplication {
    companion object : KLogging()
}

/** Starts the concert ticket flash-sale example. */
fun main(args: Array<String>) {
    runApplication<TicketFlashSaleApplication>(*args)
    TicketFlashSaleApplication.log.info { "concert_ticket_flash_sale_started" }
}
