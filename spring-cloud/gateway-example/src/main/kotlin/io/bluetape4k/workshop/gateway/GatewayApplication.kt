package io.bluetape4k.workshop.gateway

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GatewayApplication {

    companion object: KLogging() {
        val redisServer = RedisServer.Launcher.redis
    }
}

fun main(vararg args: String) {
    runApplication<GatewayApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
