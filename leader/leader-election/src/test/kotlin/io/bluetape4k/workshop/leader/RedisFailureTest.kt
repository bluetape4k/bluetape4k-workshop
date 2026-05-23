package io.bluetape4k.workshop.leader

import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.*
import io.lettuce.core.RedisClient
import io.lettuce.core.codec.StringCodec
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import java.time.Duration
import java.util.UUID
import kotlin.test.assertFailsWith
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
    fun `runIfLeader throws exception when Redis is unavailable`() {
        // Use a dedicated container — NOT the shared singleton
        @Suppress("HttpUrlsUsage")
        val redisContainer = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)
            .apply { start() }

        val redisUrl = "redis://${redisContainer.host}:${redisContainer.getMappedPort(6379)}"
        val connection = RedisClient.create(redisUrl).connect(StringCodec.UTF8)
        val options = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds)
        val elector = LettuceLeaderElector(connection, options)
        val lockName = "test:t6:${UUID.randomUUID()}"

        // Stop Redis before attempting lock acquisition
        redisContainer.stop()

        // Library must not hang silently — must throw within 10 seconds
        // Outer: assertTimeoutPreemptively guards against infinite hang (throws AssertionError on timeout)
        // Inner: assertFailsWith<Exception> verifies Redis failure propagates as an exception
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            assertFailsWith<Exception> {
                elector.runIfLeader(lockName) { "should-not-execute" }
            }
        }

        log.info { "[T6] Redis failure correctly propagated as exception — no silent hang" }
    }

    companion object : KLogging()
}
