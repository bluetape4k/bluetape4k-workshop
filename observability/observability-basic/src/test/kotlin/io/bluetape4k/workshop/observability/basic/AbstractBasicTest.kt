package io.bluetape4k.workshop.observability.basic

import io.bluetape4k.logging.KLogging
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * 모든 observability-basic integration test 의 base class 입니다.
 *
 * ## Behavior / Contract
 * - 모든 subclass 가 하나의 `MockWebServer` instance 를 공유합니다. `junit-platform.properties` 로 serial execution 합니다.
 * - 여러 subclass 가 이 instance 를 공유하므로 `@AfterAll mockServer.shutdown()` 은 의도적으로 없습니다. premature shutdown 은 sibling test 를 깨뜨릴 수 있고 cleanup 은 JVM shutdown 이 처리합니다.
 * - 각 test 는 자체 stub enqueue 를 책임집니다. shared `@BeforeEach` enqueue 는 없습니다.
 * - cross-context test pollution 을 막기 위해 `@AfterEach resetMockServerDispatcher()` 가 recorded request 를 drain 하고 queued response 를 reset 합니다. 예를 들어 stale entry 가 `TracePropagationTest` 를 깨뜨리는 상황을 방지합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractBasicTest {

    companion object : KLogging() {
        val mockServer: MockWebServer = MockWebServer().also {
            it.dispatcher = newQueueDispatcher()
            it.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("workshop.observability.inventory.base-url") { mockServer.url("/").toString() }
        }

        private fun newQueueDispatcher(): QueueDispatcher =
            QueueDispatcher().apply {
                setFailFast(
                    MockResponse.Builder()
                        .code(503)
                        .addHeader("Connection", "close")
                        .body("")
                        .build()
                )
            }
    }

    @Autowired
    protected lateinit var context: ApplicationContext

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

    @BeforeEach
    fun prepareMockServerDispatcher() {
        resetMockServerDispatcher()
    }

    @AfterEach
    fun resetMockServerDispatcher() {
        // 소비되지 않은 recorded request 를 drain 하여 stale entry 가 오염시키지 않게 합니다.
        // takeRequest() 를 호출하는 cross-context test(예: TracePropagationTest)를 보호합니다.
        @Suppress("ControlFlowWithEmptyBody")
        while (mockServer.takeRequest(0, TimeUnit.MILLISECONDS) != null) { /* drain */ }
        mockServer.dispatcher.close()
        mockServer.dispatcher = newQueueDispatcher()
    }

    /**
     * 주어진 [itemId] 와 [available] count 에 대한 successful inventory response 를 enqueue 합니다.
     */
    protected fun enqueueSuccessInventory(itemId: Long = 1L, available: Int = 50) {
        val json = """{"itemId":$itemId,"available":$available}"""
        mockServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Connection", "close")
                .body(json)
                .build()
        )
    }
}
