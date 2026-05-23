package io.bluetape4k.workshop.observability.basic

import io.bluetape4k.logging.KLogging
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Base class for all observability-basic integration tests.
 *
 * ## Behavior / Contract
 * - Shares a single `MockWebServer` instance across all subclasses (serial execution via `junit-platform.properties`).
 * - `@AfterAll mockServer.shutdown()` is intentionally absent: multiple subclasses share this instance,
 *   so premature shutdown would break sibling tests. JVM shutdown handles cleanup.
 * - Each test is responsible for enqueuing its own stubs (no shared `@BeforeEach` enqueue).
 * - `@AfterEach resetMockServerDispatcher()` drains recorded requests and resets queued responses
 *   to prevent cross-context test pollution (e.g. stale entries breaking `TracePropagationTest`).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractBasicTest {

    companion object : KLogging() {
        val mockServer: MockWebServer = MockWebServer().also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("workshop.observability.inventory.base-url") { mockServer.url("/").toString() }
        }
    }

    @Autowired
    protected lateinit var context: ApplicationContext

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

    @AfterEach
    fun resetMockServerDispatcher() {
        // Drain any unconsumed recorded requests so stale entries don't pollute
        // cross-context tests (e.g. TracePropagationTest) that call takeRequest().
        @Suppress("ControlFlowWithEmptyBody")
        while (mockServer.takeRequest(0, TimeUnit.MILLISECONDS) != null) { /* drain */ }
        mockServer.dispatcher = QueueDispatcher()
    }

    /**
     * Enqueues a successful inventory response for the given [itemId] and [available] count.
     */
    protected fun enqueueSuccessInventory(itemId: Long = 1L, available: Int = 50) {
        val json = """{"itemId":$itemId,"available":$available}"""
        mockServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(json)
                .build()
        )
    }
}
