package io.bluetape4k.workshop.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.coroutines.handler.CoroutineHandler
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.server.coRouter

@SpringBootApplication
class CoroutinesDemoApplication {

    companion object: KLoggingChannel()

    @Bean
    fun routes(coroutineHandler: CoroutineHandler) = coRouter {
        GET("/", coroutineHandler::index)
        GET("/suspend", coroutineHandler::suspending)
        GET("/deferred", coroutineHandler::deferred)
        GET("/sequential-flow", coroutineHandler::sequentialFlow)
        GET("/concurrent-flow", coroutineHandler::concurrentFlow)
        GET("/error", coroutineHandler::error)
    }
}

fun main(vararg args: String) {
    runApplication<CoroutinesDemoApplication>(*args)
}
