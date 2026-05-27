package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.*
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.codec.StringCodec
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * T6: Redis failure educational smoke test.
 *
 * Demonstrates library behavior when Redis becomes unavailable mid-operation.
 * Uses an isolated Testcontainers Redis container that is stopped before the test.
 *
 * IMPORTANT: Does NOT use `RedisServer.Launcher.redis` (the shared singleton).
 * Stopping the singleton would break all other tests.
 *
 * Excluded from the default `test` task via `junit.jupiter.execution.exclude.tags=smoke`.
 */
@Tag("smoke")
class RedisFailureTest {

    @Test
    fun `runIfLeader throws exception when Redis is unavailable`() = runSuspendIO(timeout = 10.seconds) {
        // Use a dedicated container — NOT the shared singleton
        val redisContainer = RedisServer(image = RedisServer.IMAGE, tag = "7-alpine").apply { start() }

        val connection = RedisClient.create(redisContainer.url).connect(StringCodec.UTF8)
        val options = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds)
        val elector = LettuceLeaderElector(connection, options)
        val lockName = "test:t6:${Base58.randomString(8)}"

        // Stop Redis before attempting lock acquisition
        redisContainer.stop()

        // runSuspendIO timeout guards against infinite hang; assertFailsWith verifies Redis failure propagation.
        assertFailsWith<Exception> {
            elector.runIfLeader(lockName) { "should-not-execute" }
        }

        log.info { "[T6] Redis failure correctly propagated as exception — no silent hang" }
    }

    companion object : KLogging()
}
