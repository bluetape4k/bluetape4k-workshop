package io.bluetape4k.workshop.observability.advanced

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Base class for all observability-advanced integration tests.
 *
 * ## Behavior / Contract
 * - Uses `RedisServer.Launcher.redis` singleton Testcontainer (no `@Testcontainers` needed).
 * - `@DynamicPropertySource` overrides `workshop.observability.redis.url` with the container URL.
 * - `redis.url` returns `redis://host:port` format as required by Redisson.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractAdvancedTest {

    companion object : KLogging() {
        val redis = RedisServer.Launcher.redis

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("workshop.observability.redis.url") { redis.url }
        }
    }

    @Autowired
    protected lateinit var context: ApplicationContext

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }
}
