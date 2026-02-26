package io.bluetape4k.workshop.exposed.spring.boot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@EnableAsync
@SpringBootApplication(exclude = [DataSourceTransactionManagerAutoConfiguration::class])
class Application {
}

fun main(vararg args: String) {
    runApplication<Application>(*args)
}
