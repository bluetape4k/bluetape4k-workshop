package io.bluetape4k.workshop.gateway

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GatewayApplication {

    companion object: KLoggingChannel() {
        val redisServer = RedisServer.Launcher.redis
    }
}

fun main(vararg args: String) {
    runApplication<GatewayApplication>(*args)
}
