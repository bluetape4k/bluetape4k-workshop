package io.bluetape4k.workshop.observability.basic.service

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.workshop.observability.basic.AbstractBasicTest
import io.bluetape4k.workshop.observability.basic.TestObservationConfig
import io.bluetape4k.workshop.observability.basic.model.Order
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@Import(TestObservationConfig::class)
class OrderServiceTest : AbstractBasicTest() {

    @Autowired
    private lateinit var orderService: OrderService

    @Autowired
    private lateinit var testRegistry: TestObservationRegistry

    @BeforeEach
    fun clearRegistry() {
        testRegistry.clear()
    }

    @AfterEach
    fun assertNoLeakedObservation() {
        TestObservationRegistryAssert.assertThat(testRegistry)
            .doesNotHaveAnyRemainingCurrentObservation()
    }

    @Test
    fun `getOrder - order service fetch span started and stopped`() = runSuspendIO {
        enqueueSuccessInventory(itemId = 42L, available = 10)

        val result = orderService.getOrder(42L)

        result shouldBeEqualTo Order(
            id = 42L,
            itemId = 42L,
            quantity = 1,
            inventoryAvailable = 10,
        )
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("order.service.fetch")
            .that()
            .hasBeenStarted()
            .hasBeenStopped()
    }

    @Test
    fun `getOrder - returns null when inventory client returns 404`() = runSuspendIO {
        mockServer.enqueue(
            MockResponse.Builder()
                .code(404)
                .body("")
                .build()
        )

        val result = orderService.getOrder(99L)

        result.shouldBeNull()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("order.service.fetch")
            .that()
            .hasBeenStarted()
            .hasBeenStopped()
    }

    @Test
    fun `getOrder - observation records error on 5xx`() = runSuspendIO {
        mockServer.enqueue(
            MockResponse.Builder()
                .code(500)
                .body("")
                .build()
        )

        try {
            orderService.getOrder(1L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // expected
        }

        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("order.service.fetch")
            .that()
            .hasBeenStarted()
            .hasBeenStopped()
            .hasError()
    }

    @Test
    fun `getOrder - observation stopped even on cancellation`() = runSuspendIO {
        mockServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"itemId":1,"available":5}""")
                .bodyDelay(500L, TimeUnit.MILLISECONDS)
                .build()
        )

        supervisorScope {
            val job = launch { orderService.getOrder(1L) }
            delay(50.milliseconds)
            job.cancel()
            job.join()
        }

        TestObservationRegistryAssert.assertThat(testRegistry)
            .doesNotHaveAnyRemainingCurrentObservation()
    }
}
