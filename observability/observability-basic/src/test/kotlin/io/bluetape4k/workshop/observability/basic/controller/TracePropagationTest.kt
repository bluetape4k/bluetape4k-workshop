package io.bluetape4k.workshop.observability.basic.controller

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.observability.basic.AbstractBasicTest
import org.junit.jupiter.api.Test
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import java.util.concurrent.TimeUnit

/**
 * Verifies W3C traceparent header propagation from inbound HTTP request to downstream WebClient call.
 *
 * ## Why a separate test class
 * - [OrderControllerTest] imports [io.bluetape4k.workshop.observability.basic.TestObservationConfig],
 *   which replaces the real `ObservationRegistry` with a `TestObservationRegistry` (no Tracer attached).
 * - Without a real Tracer, the WebClient cannot inject a `traceparent` header in outbound requests.
 * - This class uses `@AutoConfigureTracing` to ensure the full Micrometer + OpenTelemetry tracing
 *   bridge is active, which enables traceparent to be injected in outbound WebClient requests.
 */
@AutoConfigureTracing
class TracePropagationTest : AbstractBasicTest() {

    @Test
    fun `GET orders id - traceparent header propagated to downstream`() = runSuspendIO {
        enqueueSuccessInventory(itemId = 1L, available = 5)

        webTestClient.get()
            .uri("/orders/1")
            .exchange()
            .expectStatus().isOk

        val request = mockServer.takeRequest(2, TimeUnit.SECONDS)
        request.shouldNotBeNull()
        request.headers["traceparent"].shouldNotBeNull()
    }
}
