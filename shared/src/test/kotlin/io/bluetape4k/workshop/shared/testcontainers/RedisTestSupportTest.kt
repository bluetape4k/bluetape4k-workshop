package io.bluetape4k.workshop.shared.testcontainers

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import java.util.function.Supplier

class RedisTestSupportTest {

    @Test
    fun `registers Redis endpoint properties`() {
        // Given
        val registry = RecordingRegistry()

        // When
        RedisTestSupport.registerRedisProperties(registry)

        // Then
        registry.values.keys shouldHaveSize 3
        registry.values.keys shouldBeEqualTo setOf(
            "testcontainers.redis.host",
            "testcontainers.redis.port",
            "testcontainers.redis.url",
        )
        registry.values["testcontainers.redis.host"] shouldBeEqualTo RedisTestSupport.redis.host
        registry.values["testcontainers.redis.port"] shouldBeEqualTo RedisTestSupport.redis.port
        registry.values["testcontainers.redis.url"] shouldBeEqualTo RedisTestSupport.redis.url
    }

    private class RecordingRegistry : DynamicPropertyRegistry {
        val values = linkedMapOf<String, Any?>()

        override fun add(name: String, valueSupplier: Supplier<Any>) {
            values[name] = valueSupplier.get()
        }
    }
}
