package io.bluetape4k.workshop.r2dbc.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.r2dbc.handler.UserHandler
import io.r2dbc.spi.ConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.http.MediaType
import org.springframework.r2dbc.connection.init.CompositeDatabasePopulator
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.reactive.function.server.coRouter

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableR2dbcRepositories
class WebfluxR2dbcConfiguration {

    companion object: KLoggingChannel()

    @Bean
    fun userRoute(userHandler: UserHandler) = coRouter {
        accept(MediaType.APPLICATION_JSON).nest {
            GET("/users", userHandler::findAll)
            GET("/users/search", userHandler::search)
            GET("/users/{id}", userHandler::findUser)
            POST("/users", userHandler::addUser)
            PUT("/users/{id}", userHandler::updateUser)
            DELETE("/users/{id}", userHandler::deleteUser)
        }
    }

    /**
     * Initializes the database schema and seed data on startup.
     *
     * Uses [ConnectionFactoryInitializer] to explicitly run `data/schema.sql` followed by
     * `data/data.sql` via R2DBC. This replaces `spring.sql.init` which is unreliable
     * for R2DBC embedded databases under Spring Boot 4.
     */
    @Bean
    fun databaseInitializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer {
        log.info { "Initializing database schema and seed data..." }
        return ConnectionFactoryInitializer().apply {
            setConnectionFactory(connectionFactory)
            setDatabasePopulator(
                CompositeDatabasePopulator().apply {
                    addPopulators(ResourceDatabasePopulator(ClassPathResource("data/schema.sql")))
                    addPopulators(ResourceDatabasePopulator(ClassPathResource("data/data.sql")))
                }
            )
        }
    }
}
