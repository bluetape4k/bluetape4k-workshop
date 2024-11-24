package io.bluetape4k.workshop.cache.caffeine.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.cache.caffeine.AbstractCaffeineCacheApplicationTest
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager

class CaffeineCacheConfigTest: AbstractCaffeineCacheApplicationTest() {

    companion object: KLogging()

    @Autowired
    private val cacheManager: CacheManager = uninitialized()

    @Test
    fun `context loading`() {
        cacheManager.shouldNotBeNull()
    }
}
