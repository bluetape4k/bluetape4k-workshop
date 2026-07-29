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
 * 공유 Spring Boot context 와 Testcontainers Redis 를 제공하는 benchmark 기본 state 입니다.
 *
 * ## Lifecycle
 * subclass 는 각자의 `@Setup` override 에서 [setup] 을 호출해야 합니다.
 * 상속된 [teardown] 은 각 trial 뒤 Spring context 를 한 번 닫습니다.
 *
 * **여기에는 `@Setup` annotation 을 두지 않습니다**. abstract parent 와 concrete override 양쪽에
 * annotation 을 붙이면 JMH scanner 가 trial 마다 setup 을 두 번 호출해 Spring context 두 개를 시작합니다.
 * concrete benchmark 는 [teardown] 을 override 하지 않으므로, 상속된 단일 annotation 은 안전합니다.
 *
 * Redis 는 bluetape4k 의 [RedisServer.Launcher] singleton 으로 시작하며,
 * 같은 JVM 의 모든 benchmark 가 container 를 공유합니다.
 */
abstract class AbstractCacheBenchmark {
    companion object : KLoggingChannel() {
        /** 전체 JVM 에서 한 번 시작되는 공유 Redis container 입니다. */
        val redis: RedisServer by lazy { RedisServer.Launcher.redis }
    }

    protected lateinit var context: ConfigurableApplicationContext

    /** concrete subclass 의 `@Setup` annotated method 에서 호출합니다. */
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

    /** 각 trial 뒤 benchmark Spring context 를 닫습니다. */
    @TearDown
    open fun teardown() {
        beforeTeardown()
        if (::context.isInitialized) {
            context.close()
            log.info { "Spring context closed" }
        }
    }

    /** context 가 닫히기 전 benchmark 가 strategy-specific 작업을 마칠 수 있게 합니다. */
    protected open fun beforeTeardown() = Unit

    /** 초기화된 dataset 범위(1..DataInitConfig.PRODUCT_COUNT)의 유효한 product ID 를 반환합니다. */
    protected fun sampleId(iteration: Int): Long = ((iteration % DataInitConfig.PRODUCT_COUNT) + 1).toLong()
}
