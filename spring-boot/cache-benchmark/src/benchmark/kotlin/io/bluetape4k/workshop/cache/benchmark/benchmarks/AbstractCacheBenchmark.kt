package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.cache.benchmark.CacheBenchmarkApplication
import io.bluetape4k.workshop.cache.benchmark.config.DataInitConfig
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/**
 * Base benchmark state providing a shared Spring Boot context and Testcontainers Redis.
 *
 * ## Lifecycle
 * Subclasses must call [setup] from their own `@Setup`-annotated override and
 * [teardown] from their own `@TearDown`-annotated override.
 *
 * **No `@Setup`/`@TearDown` annotations here** — annotating both the abstract parent
 * and each concrete override with the same annotation causes JMH's scanner to invoke
 * setup twice per trial (once from the raw-JMH annotation in the hierarchy and once
 * from the kotlinx.benchmark-mapped one), starting two Spring contexts.
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

    /** Call from the concrete subclass's `@Setup`-annotated method. */
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

    /** Call from the concrete subclass's `@TearDown`-annotated method. */
    open fun teardown() {
        if (::context.isInitialized) {
            context.close()
            log.info { "Spring context closed" }
        }
    }

    /** Returns a valid product ID within the initialized dataset (1..DataInitConfig.PRODUCT_COUNT). */
    protected fun sampleId(iteration: Int): Long = ((iteration % DataInitConfig.PRODUCT_COUNT) + 1).toLong()
}
