package io.bluetape4k.workshop.cache.redis

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class RedisCacheApplication {

    companion object: KLoggingChannel() {
        @JvmStatic
        val redisServer = RedisServer.Launcher.redis
    }
}

fun main(vararg args: String) {
    runApplication<RedisCacheApplication>(*args) {
        setAdditionalProfiles("app")
    }
}
