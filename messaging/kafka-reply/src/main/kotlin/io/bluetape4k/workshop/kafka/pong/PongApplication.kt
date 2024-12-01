package io.bluetape4k.workshop.kafka.pong

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.mq.KafkaServer
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(
    // NOTE: Spring Application Admin 의 JMX 설정을 제외합니다. (Ping Application 과 중복됨)
    exclude = [SpringApplicationAdminJmxAutoConfiguration::class]
)
class PongApplication {

    companion object: KLogging() {
        private val kafka = KafkaServer.Launcher.kafka
        const val TOPIC_PINGPONG = "pingpong"
    }
}

fun main(vararg args: String) {
    runApplication<PongApplication>(*args) {
        webApplicationType = WebApplicationType.NONE
        setDefaultProperties(
            mapOf(
                "spring.jmx.enabled" to false,
                "spring.jmx.default-domain" to "pong"
            )
        )
    }
}
