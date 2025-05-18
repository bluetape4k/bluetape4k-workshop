package io.bluetape4k.workshop.cache.caffeine

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
abstract class AbstractCaffeineCacheApplicationTest {
    companion object: KLoggingChannel()
}
