package io.bluetape4k.workshop.cache.redis.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.redis.AbstractRedisCacheTest
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager

class LettuceRedisCacheConfigurationTest(
    @Autowired private val cacheManager: CacheManager,
): AbstractRedisCacheTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        cacheManager.shouldNotBeNull()
    }

}
