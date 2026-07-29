package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

/**
 * Redis 기반 Bucket4j WebFlux 예제의 Spring Boot entry point입니다.
 *
 * companion은 공유 bluetape4k Redis Testcontainer를 시작해,
 * sample이 통합 테스트와 같은 Redis URL source로 bootstrap되도록 합니다.
 */
@SpringBootApplication(proxyBeanMethods = false)
@EnableCaching
class RedisApplication {

    companion object : KLoggingChannel() {
        @JvmStatic
        private val redisServer = RedisServer.Launcher.redis
    }
}

fun main(vararg args: String) {
    runApplication<RedisApplication>(*args)
}
