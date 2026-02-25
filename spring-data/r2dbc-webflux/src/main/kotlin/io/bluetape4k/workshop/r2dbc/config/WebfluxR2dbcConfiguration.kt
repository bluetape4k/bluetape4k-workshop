package io.bluetape4k.workshop.r2dbc.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.r2dbc.handler.UserHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.http.MediaType
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.reactive.function.server.coRouter

@Configuration
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

    // NOTE: application.yml 에 population 설정을 정의함
//    @Bean
//    fun initializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer {
//        log.info { "schema 와 data 를 추가합니다." }
//        return ConnectionFactoryInitializer().apply {
//            setConnectionFactory(connectionFactory)
//            val populator = CompositeDatabasePopulator().apply {
//                addPopulators(resourceDatabasePopulatorOf(ClassPathResource("data/schema.sql")))
//                addPopulators(resourceDatabasePopulatorOf(ClassPathResource("data/data.sql")))
//            }
//            setDatabasePopulator(populator)
//            afterPropertiesSet()
//        }
//    }
}
