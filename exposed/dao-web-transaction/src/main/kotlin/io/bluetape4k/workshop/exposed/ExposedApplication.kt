package io.bluetape4k.workshop.exposed

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
// ExposedAutoConfiguration 은 Spring Boot auto-configuration 클래스로 Exposed를 구성합니다.
// @ImportAutoConfiguration(ExposedAutoConfiguration::class)
class ExposedApplication {

    companion object: KLogging()

}

fun main(args: Array<String>) {
    runApplication<ExposedApplication>(*args)
}
