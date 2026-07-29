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
 * 모든 observability-advanced integration test 의 base class 입니다.
 *
 * ## Behavior / Contract
 * - `RedisServer.Launcher.redis` singleton Testcontainer 를 사용합니다. `@Testcontainers` 는 필요 없습니다.
 * - `@DynamicPropertySource` 는 `workshop.observability.redis.url` 을 container URL 로 override 합니다.
 * - `redis.url` 은 Redisson 이 요구하는 `redis://host:port` format 을 반환합니다.
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
