package io.bluetape4k.workshop.redis.cluster

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [RedisClusterApplication::class])
abstract class AbstractRedisClusterTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        val faker = Fakers.faker

        @JvmStatic
        fun randomKey(): String = Fakers.fixedString(32)

        @JvmStatic
        fun randomValue(): String = Fakers.fixedString(256)
    }
}
