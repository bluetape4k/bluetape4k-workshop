package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.bucket4j.config.TestRedisConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractRateLimiterApplicationTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        @DynamicPropertySource
        fun registerRedisProperties(registry: DynamicPropertyRegistry) {
            val redis = TestRedisConfig.redis
            registry.add("testcontainers.redis.url") { redis.url }
            registry.add("testcontainers.redis.host") { redis.host }
            registry.add("testcontainers.redis.port") { redis.port.toString() }
        }
    }

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    protected val client: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

}
