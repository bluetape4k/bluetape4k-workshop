package io.bluetape4k.workshop.redis

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [RedisApplication::class])
abstract class AbstractRedisTest {

    companion object: KLoggingChannel()

}
