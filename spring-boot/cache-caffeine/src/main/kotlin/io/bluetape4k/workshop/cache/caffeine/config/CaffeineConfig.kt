package io.bluetape4k.workshop.cache.caffeine.config

import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.cache.caffeine.caffeine
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.logging.KLogging
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

@Configuration
@EnableCaching
class CaffeineConfig {

    companion object: KLogging()

    @Bean
    fun cacheManager(caffeine: Caffeine<Any, Any>): CacheManager {
        return CaffeineCacheManager("cache:contries", "cache:cities").apply {
            setCaffeine(caffeine)
        }
    }

    @Bean
    fun caffeineBean(): Caffeine<Any, Any> {
        return caffeine {
            initialCapacity(100)
            maximumSize(1000)
            expireAfterAccess(5.minutes.toJavaDuration())
            recordStats()
            executor(VirtualThreadExecutor)
        }
    }
}
