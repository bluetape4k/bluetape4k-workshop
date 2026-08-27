package io.bluetape4k.workshop.r2dbc

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.r2dbc.spi.ConnectionFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ClassPathResource
import org.springframework.r2dbc.connection.init.CompositeDatabasePopulator
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator

@SpringBootApplication(proxyBeanMethods = false)
class R2dbcApplication {

    companion object : KLoggingChannel()

    @Bean
    fun initializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer {
        log.info { "Initialize Database ..." }
        return ConnectionFactoryInitializer().apply {
            setConnectionFactory(connectionFactory)
            val populator = CompositeDatabasePopulator().apply {
                addPopulators(ResourceDatabasePopulator(ClassPathResource("data/schema.sql")))
            }
            setDatabasePopulator(populator)
            afterPropertiesSet()
        }
    }
}

fun main(vararg args: String) {
    runApplication<R2dbcApplication>(*args)
}
