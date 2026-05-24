package io.bluetape4k.workshop.bucket4j.advanced

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.bucket4j.advanced.config.TestRedisConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractBucket4jAdvancedTest {

    companion object : KLoggingChannel() {
        @JvmStatic
        @DynamicPropertySource
        fun registerRedisProperties(registry: DynamicPropertyRegistry) {
            val redis = TestRedisConfig.redis
            registry.add("testcontainers.redis.url") { redis.url }
            registry.add("testcontainers.redis.host") { redis.host }
            registry.add("testcontainers.redis.port") { redis.port.toString() }
        }
    }

    @LocalServerPort
    protected var port: Int = 0

    // Use bindToServer so requests have a real TCP remote address (127.0.0.1).
    // This is required for IP-based rate limiting to work correctly in tests.
    protected val client: WebTestClient by lazy {
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .build()
    }
}
