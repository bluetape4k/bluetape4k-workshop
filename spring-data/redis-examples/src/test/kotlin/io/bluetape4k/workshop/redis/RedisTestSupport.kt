package io.bluetape4k.workshop.redis

import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.test.context.DynamicPropertyRegistry

internal object RedisTestSupport {

    val redis: RedisServer = RedisServer.Launcher.redis

    fun registerRedisProperties(registry: DynamicPropertyRegistry) {
        registry.add("testcontainers.redis.host") { redis.host }
        registry.add("testcontainers.redis.port") { redis.port }
        registry.add("testcontainers.redis.url") { redis.url }
    }
}
