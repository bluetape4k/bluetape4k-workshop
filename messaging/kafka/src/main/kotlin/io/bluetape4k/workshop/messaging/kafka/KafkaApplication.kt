package io.bluetape4k.workshop.messaging.kafka

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.mq.KafkaServer
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka

@SpringBootApplication
@EnableKafka
class KafkaApplication {

    companion object: KLoggingChannel() {
        val kafka = KafkaServer.Launcher.kafka
    }
}

fun main(args: Array<String>) {
    runApplication<KafkaApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
