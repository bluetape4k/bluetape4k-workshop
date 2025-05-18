package io.bluetape4k.workshop.redis.reactive

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [ReactiveRedisConfiguration::class])
abstract class AbstractReactiveRedisTest {

    companion object: KLoggingChannel()

}
