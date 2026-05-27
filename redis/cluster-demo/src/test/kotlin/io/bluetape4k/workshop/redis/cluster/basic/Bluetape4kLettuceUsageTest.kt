package io.bluetape4k.workshop.redis.cluster.basic

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.redis.lettuce.awaitSuspending
import io.bluetape4k.redis.lettuce.codec.LettuceLongCodec
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.redis.cluster.AbstractRedisClusterTest
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test

class Bluetape4kLettuceUsageTest: AbstractRedisClusterTest() {

    @Test
    fun `bluetape4k lettuce client supports typed async round trip`() = runSuspendIO {
        val redis = RedisServer.Launcher.redis
        val client = LettuceClients.clientOf(redis.url)

        try {
            val commands = LettuceClients.asyncCommands(client, LettuceLongCodec)
            val key = "lettuce:${randomKey()}"

            commands.set(key, 42L).awaitSuspending() shouldBeEqualTo "OK"
            commands.get(key).awaitSuspending() shouldBeEqualTo 42L
        } finally {
            LettuceClients.shutdown(client)
        }
    }
}
