package io.bluetape4k.workshop.exposed.r2dbc

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.exposed.r2dbc.handler.UserHandler
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.coRouter

@SpringBootApplication
class WebfluxR2dbcExposedApplication {

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
}

fun main(vararg args: String) {
    runApplication<WebfluxR2dbcExposedApplication>(*args)
}
