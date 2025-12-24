package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class VirtualThreadControllerTest: AbstractVirtualThreadMvcTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get response with virtual thread`() = runSuspendIO {
        val response = webTestClient
            .get()
            .uri("/virtual-thread")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<String>().responseBody
            .awaitSingle()

        log.debug { "Response: $response" }
        response.shouldNotBeEmpty()
    }

    @Test
    fun `run multiple tasks with StructuredTaskScope`() = runSuspendIO {
        val response = webTestClient
            .get()
            .uri("/virtual-thread/multi")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<String>().responseBody
            .awaitSingle()

        log.debug { "Response: $response" }
        response.shouldNotBeEmpty()
    }

    @Test
    fun `run multiple tasks with virtualFutureAll`() = runSuspendIO {
        val response = webTestClient
            .get()
            .uri("/virtual-thread/virtualFutureAll")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<String>().responseBody
            .awaitSingle()

        log.debug { "Response: $response" }
        response.shouldNotBeEmpty()
    }
}
