package io.bluetape4k.workshop.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIdempotencyTest {

    companion object : KLogging() {
        @JvmStatic
        val redis: RedisServer = RedisServer.Launcher.redis

        @JvmStatic
        @DynamicPropertySource
        fun registerRedisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.port.toString() }
        }
    }

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    protected val client: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }
}
