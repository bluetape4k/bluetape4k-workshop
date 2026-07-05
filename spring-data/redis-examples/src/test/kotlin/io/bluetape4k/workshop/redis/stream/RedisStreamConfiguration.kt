package io.bluetape4k.workshop.redis.stream

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import org.springframework.data.redis.stream.StreamReceiver

/**
 * [io.bluetape4k.workshop.redis.reactive.ReactiveRedisConfiguration] 에
 */
@SpringBootApplication(proxyBeanMethods = false)
class RedisStreamConfiguration(
    private val factory: RedisConnectionFactory,
    private val reactiveFactory: ReactiveRedisConnectionFactory,
) {

    companion object : KLoggingChannel() {
        @JvmStatic
        val redis = RedisServer.Launcher.redis
    }

    @Bean
    fun streamMessageListenerContainer(): StreamMessageListenerContainer<String, MapRecord<String, String, String>> {
        return StreamMessageListenerContainer.create(factory)
    }

    /**
     * Redis Stream 을 Reactive 방식으로 읽기 위한 [StreamReceiver]를 생성합니다.
     */
    @Bean
    fun streamReceiver(): StreamReceiver<String, MapRecord<String, String, String>> {
        return StreamReceiver.create(reactiveFactory)
    }
}
