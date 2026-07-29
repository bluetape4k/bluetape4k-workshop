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

    // 요청에 실제 TCP remote address(127.0.0.1)가 생기도록 bindToServer를 사용합니다.
    // IP 기반 rate limiting이 테스트에서 올바르게 동작하려면 이 주소가 필요합니다.
    protected val client: WebTestClient by lazy {
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .build()
    }
}
