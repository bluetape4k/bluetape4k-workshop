package io.bluetape4k.workshop.redis.reactive

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.shared.testcontainers.RedisTestSupport
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(classes = [ReactiveRedisConfiguration::class])
abstract class AbstractReactiveRedisTest {

    companion object : KLoggingChannel() {

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            RedisTestSupport.registerRedisProperties(registry)
        }
    }

}
