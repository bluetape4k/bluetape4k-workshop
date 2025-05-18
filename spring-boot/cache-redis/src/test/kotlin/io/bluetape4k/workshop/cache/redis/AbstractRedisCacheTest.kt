package io.bluetape4k.workshop.cache.redis

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
abstract class AbstractRedisCacheTest {

    companion object: KLoggingChannel()

}
