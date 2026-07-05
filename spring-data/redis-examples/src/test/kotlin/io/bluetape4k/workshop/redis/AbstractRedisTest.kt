package io.bluetape4k.workshop.redis

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(classes = [RedisApplication::class])
abstract class AbstractRedisTest {

    companion object : KLoggingChannel() {

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            RedisTestSupport.registerRedisProperties(registry)
        }
    }

}
