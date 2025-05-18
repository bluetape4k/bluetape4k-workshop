package io.bluetape4k.workshop.jmolecules.example

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.info
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

private val log = KotlinLogging.logger { }

@SpringBootApplication
class KotlinExample

fun main(vararg args: String) {
    val context = runApplication<KotlinExample>(*args)

    val repository = context.getBean<OrderRepository>()
    val order = Order()
    log.info { "Saving new order. $order" }

    val saved = repository.save(order)
    log.info { "Saved order. $saved" }
}
