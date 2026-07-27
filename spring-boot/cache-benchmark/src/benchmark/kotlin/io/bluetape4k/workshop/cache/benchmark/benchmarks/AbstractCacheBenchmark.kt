package io.bluetape4k.workshop.cache.benchmark.benchmarks

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.cache.benchmark.CacheBenchmarkApplication
import io.bluetape4k.workshop.cache.benchmark.config.DataInitConfig
import kotlinx.benchmark.TearDown
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/**
 * Base benchmark state providing a shared Spring Boot context and Testcontainers Redis.
 *
 * ## Lifecycle
 * Subclasses must call [setup] from their own `@Setup`-annotated override.
 * The inherited [teardown] closes the Spring context once after each trial.
 *
 * **No `@Setup` annotation here** — annotating both the abstract parent and each
 * concrete override causes JMH's scanner to invoke setup twice per trial, starting
 * two Spring contexts. Concrete benchmarks do not override [teardown], so its single
 * inherited annotation is safe.
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
        val namespace = "benchmark-${System.nanoTime()}"

        context = SpringApplicationBuilder(CacheBenchmarkApplication::class.java)
            .properties(
                "spring.datasource.url=jdbc:h2:mem:$namespace;DB_CLOSE_DELAY=-1",
                "cache.benchmark.namespace=$namespace",
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

    /** Close the benchmark Spring context after each trial. */
    @TearDown
    open fun teardown() {
        beforeTeardown()
        if (::context.isInitialized) {
            context.close()
            log.info { "Spring context closed" }
        }
    }

    /** Allow a benchmark to finish strategy-specific work before the context closes. */
    protected open fun beforeTeardown() = Unit

    /** Returns a valid product ID within the initialized dataset (1..DataInitConfig.PRODUCT_COUNT). */
    protected fun sampleId(iteration: Int): Long = ((iteration % DataInitConfig.PRODUCT_COUNT) + 1).toLong()
}
