package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.cache.benchmark.CacheBenchmarkApplication
import io.bluetape4k.workshop.cache.benchmark.config.DataInitConfig
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.TearDown
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/**
 * Base benchmark state providing a shared Spring Boot context and Testcontainers Redis.
 *
 * ## Lifecycle
 * - [setup]: start Redis container + Spring Boot context (once per JMH trial)
 * - [teardown]: close Spring Boot context (once per JMH trial)
 *
 * Redis is started via bluetape4k's [RedisServer.Launcher] singleton — the container
 * is shared across all benchmarks in the same JVM.
 */
abstract class AbstractCacheBenchmark {
    companion object : KLoggingChannel() {
        /** Shared Redis container — started once for the entire JVM. */
        val redis: RedisServer by lazy { RedisServer.Launcher.redis }
    }

    protected lateinit var context: ConfigurableApplicationContext

    @Setup(Level.Trial)
    open fun setup() {
        val redisHost = redis.host
        val redisPort = redis.port

        context = SpringApplicationBuilder(CacheBenchmarkApplication::class.java)
            .properties(
                "spring.datasource.url=jdbc:h2:mem:benchmark-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                "spring.data.redis.host=$redisHost",
                "spring.data.redis.port=$redisPort",
                "REDIS_HOST=$redisHost",
                "REDIS_PORT=$redisPort",
                "logging.level.root=WARN",
                "logging.level.io.bluetape4k.workshop.cache.benchmark=WARN",
            )
            .run()

        log.info { "Spring context started. Redis: $redisHost:$redisPort" }
    }

    @TearDown(Level.Trial)
    open fun teardown() {
        if (::context.isInitialized) {
            context.close()
            log.info { "Spring context closed" }
        }
    }

    /** Returns a valid product ID within the initialized dataset (1..DataInitConfig.PRODUCT_COUNT). */
    protected fun sampleId(iteration: Int): Long = ((iteration % DataInitConfig.PRODUCT_COUNT) + 1).toLong()
}
