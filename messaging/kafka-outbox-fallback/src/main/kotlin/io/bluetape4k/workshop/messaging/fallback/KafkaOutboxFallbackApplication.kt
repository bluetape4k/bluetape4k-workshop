package io.bluetape4k.workshop.messaging.fallback

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
class KafkaOutboxFallbackApplication

fun main(args: Array<String>) {
    runApplication<KafkaOutboxFallbackApplication>(*args)
}
