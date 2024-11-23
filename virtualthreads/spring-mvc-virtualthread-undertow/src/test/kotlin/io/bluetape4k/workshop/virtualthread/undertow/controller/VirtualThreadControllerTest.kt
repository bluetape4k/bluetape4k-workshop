package io.bluetape4k.workshop.virtualthread.undertow.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.virtualthread.undertow.AbstractUndertowVirtualThreadMvcTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient

class VirtualThreadControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractUndertowVirtualThreadMvcTest() {

    companion object: KLogging()

    @Test
    fun `get response with virtual thread`() {
        client.get()
            .uri("/virtual-thread")
            .exchange()
            .expectStatus().is2xxSuccessful
    }

    @Test
    fun `run multiple tasks with virtual thread`() {
        client.get()
            .uri("/virtual-thread/multi")
            .exchange()
            .expectStatus().is2xxSuccessful
    }
}
