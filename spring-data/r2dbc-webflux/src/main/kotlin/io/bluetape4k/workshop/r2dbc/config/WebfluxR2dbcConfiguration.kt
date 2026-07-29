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

    companion object : KLoggingChannel()

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
     * 애플리케이션 시작 시 데이터베이스 스키마와 seed 데이터를 초기화합니다.
     *
     * [ConnectionFactoryInitializer] 를 사용해 R2DBC 로 `data/schema.sql` 다음에 `data/data.sql` 을
     * 명시적으로 실행합니다. Spring Boot 4 의 R2DBC embedded database 에서 신뢰하기 어려운
     * `spring.sql.init` 을 이 방식으로 대체합니다.
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
