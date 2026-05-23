package io.bluetape4k.workshop.observability.basic.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.observability.basic.AbstractBasicTest
import io.bluetape4k.workshop.observability.basic.TestObservationConfig
import io.bluetape4k.workshop.observability.basic.model.Order
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

@Import(TestObservationConfig::class)
class OrderControllerTest : AbstractBasicTest() {

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
    fun `GET orders id - 200 OK with order service fetch span`() = runSuspendIO {
        enqueueSuccessInventory(itemId = 42L, available = 10)

        val response = webTestClient.get()
            .uri("/orders/42")
            .exchange()
            .expectStatus().isOk
            .expectBody(Order::class.java)
            .returnResult()

        val body = response.responseBody.shouldNotBeNull()
        body.id shouldBeEqualTo 42L

        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("order.service.fetch")
            .that()
            .hasBeenStarted()
            .hasBeenStopped()
    }

}
