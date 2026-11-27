package io.bluetape4k.workshop.shared.redis

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull

class LettuceRedisClientFactoryTest {

    @Test
    fun `factory delegates client creation and shutdown to tuned lettuce clients`() {
        val factory = LettuceRedisClientFactory()
        val endpoint = RedisEndpoint("localhost", 6380, "redis://localhost:6380")

        val client = factory.clientOf(endpoint)
        try {
            assertNotNull(client)
        } finally {
            factory.close(client)
        }
    }
}
