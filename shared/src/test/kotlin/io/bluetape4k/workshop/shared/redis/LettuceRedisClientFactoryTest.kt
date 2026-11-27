package io.bluetape4k.workshop.shared.redis

import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test

class LettuceRedisClientFactoryTest {

    @Test
    fun `factory delegates client creation and shutdown to tuned lettuce clients`() {
        val factory = LettuceRedisClientFactory()
        val endpoint = RedisEndpoint("localhost", 6380, "redis://localhost:6380")

        val client = factory.clientOf(endpoint)
        try {
            client.shouldNotBeNull()
        } finally {
            factory.close(client)
        }
    }
}
