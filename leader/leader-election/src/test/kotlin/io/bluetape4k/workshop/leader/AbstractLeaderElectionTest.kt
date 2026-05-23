package io.bluetape4k.workshop.leader

import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Base class for all leader election tests.
 *
 * Provides a shared Testcontainers Redis instance (via bluetape4k Launcher singleton pattern)
 * and factory helpers for creating [LettuceLeaderElector] instances with isolated connections.
 *
 * ## Behavior / Contract
 * - Uses `RedisServer.Launcher.redis` singleton — no `@Testcontainers` annotation needed.
 * - Each test should create its own [StatefulRedisConnection] via [newConnection] to avoid
 *   shared state between concurrent elector instances.
 * - Default options: `waitTime = 100ms`, `leaseTime = 5s` for fast, deterministic tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractLeaderElectionTest {

    companion object : KLogging() {
        /** Shared Redis Testcontainer — started once for the entire test suite. */
        val redis = RedisServer.Launcher.redis

        /** Redis URL from the running container. */
        val redisUrl: String get() = redis.url

        /** Default options for deterministic testing: fast wait, short lease. */
        val defaultOptions = LeaderElectionOptions(
            waitTime = 100.milliseconds,
            leaseTime = 5.seconds,
        )
    }

    /** Opens a fresh [StatefulRedisConnection]. Caller is responsible for closing it. */
    protected fun newConnection(): StatefulRedisConnection<String, String> =
        RedisClient.create(redisUrl).connect(StringCodec.UTF8)

    /** Creates a new [LettuceLeaderElector] with its own connection and the given options. */
    protected fun newElector(
        options: LeaderElectionOptions = defaultOptions,
    ): LettuceLeaderElector = LettuceLeaderElector(newConnection(), options)
}
