package io.bluetape4k.workshop.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Idempotency Key workshop application 입니다.
 *
 * Redis(Redisson RMapCache)와 Kotlin coroutine 기반 Spring Boot WebFlux 로
 * 중복에 안전한 command handling 을 보여줍니다.
 */
@SpringBootApplication(proxyBeanMethods = false)
class IdempotencyApplication {

    companion object : KLogging() {
        /**
         * local development 와 test 를 위해 Testcontainers 로 시작되는 singleton Redis server 입니다.
         */
        @JvmStatic
        val redisServer: RedisServer = RedisServer.Launcher.redis
    }
}

fun main(args: Array<String>) {
    runApplication<IdempotencyApplication>(*args)
}
