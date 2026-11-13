package io.bluetape4k.workshop.cache.benchmark.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisTemplateConfig {
    companion object: KLoggingChannel()

    @Bean
    fun productRedisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, Product> {
        return RedisTemplate<String, Product>().apply {
            setConnectionFactory(connectionFactory)
            keySerializer = StringRedisSerializer()
            valueSerializer = JacksonJsonRedisSerializer(Product::class.java)
            hashKeySerializer = StringRedisSerializer()
            hashValueSerializer = JacksonJsonRedisSerializer(Product::class.java)
        }
    }
}
