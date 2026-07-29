package io.bluetape4k.workshop.observability.basic.controller

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.observability.basic.AbstractBasicTest
import org.junit.jupiter.api.Test
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import java.util.concurrent.TimeUnit

/**
 * inbound HTTP request 에서 downstream WebClient call 로 W3C traceparent header 가 전파되는지 검증합니다.
 *
 * ## Why a separate test class
 * - [OrderControllerTest] 는 [io.bluetape4k.workshop.observability.basic.TestObservationConfig] 를 import 하며, 이 config 는 real `ObservationRegistry` 를 Tracer 가 붙지 않은 `TestObservationRegistry` 로 교체합니다.
 * - real Tracer 가 없으면 WebClient 는 outbound request 에 `traceparent` header 를 inject 할 수 없습니다.
 * - 이 class 는 `@AutoConfigureTracing` 으로 전체 Micrometer + OpenTelemetry tracing bridge 가 활성화되도록 보장하며, 이를 통해 outbound WebClient request 에 traceparent 를 inject 할 수 있습니다.
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
