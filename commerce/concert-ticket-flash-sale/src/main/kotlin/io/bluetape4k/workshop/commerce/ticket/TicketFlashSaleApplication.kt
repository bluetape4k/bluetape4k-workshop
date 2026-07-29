package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** Java 25 Spring MVC 기반 concert ticket flash-sale 예제를 기동합니다. */
@SpringBootApplication
@ConfigurationPropertiesScan
class TicketFlashSaleApplication {
    companion object : KLogging()
}

/** concert ticket flash-sale 예제를 시작합니다. */
fun main(args: Array<String>) {
    runApplication<TicketFlashSaleApplication>(*args)
    TicketFlashSaleApplication.log.info { "concert_ticket_flash_sale_started" }
}
