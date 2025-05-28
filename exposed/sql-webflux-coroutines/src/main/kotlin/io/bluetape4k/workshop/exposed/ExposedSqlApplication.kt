package io.bluetape4k.workshop.exposed

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.database.MySQL8Server
import org.jetbrains.exposed.v1.spring.boot.autoconfigure.ExposedAutoConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile

@SpringBootApplication
// ExposedAutoConfiguration is a Spring Boot auto-configuration class that configures Exposed.
@ImportAutoConfiguration(ExposedAutoConfiguration::class)
class ExposedApplication {

    companion object: KLoggingChannel()

    /**
     * profile: mysql 일 경우 Testcontainers 를 이용하여 MySQL8Server 를 실행합니다.
     */
    @Bean
    @Profile("mysql")
    fun mySqlServer(): MySQL8Server {
        return MySQL8Server.Launcher.mysql
    }
}

fun main(args: Array<String>) {
    runApplication<ExposedApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
