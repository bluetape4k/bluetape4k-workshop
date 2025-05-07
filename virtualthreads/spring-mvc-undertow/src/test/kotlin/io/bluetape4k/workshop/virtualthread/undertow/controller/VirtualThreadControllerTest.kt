package io.bluetape4k.workshop.virtualthread.undertow.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.virtualthread.undertow.AbstractUndertowVirtualThreadMvcTest
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class VirtualThreadControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractUndertowVirtualThreadMvcTest() {

    companion object: KLogging()

    @Test
    fun `get response with virtual thread`() = runSuspendIO {
        val response = client
            .httpGet("/virtual-thread")
            .returnResult<String>().responseBody
            .awaitSingle()

        log.debug { "Response: $response" }
        response.shouldNotBeEmpty()
    }

    @Test
    fun `run multiple tasks with virtual thread`() = runSuspendIO {
        val response = client
            .httpGet("/virtual-thread/multi")
            .returnResult<String>().responseBody
            .awaitSingle()

        log.debug { "Response: $response" }
        response.shouldNotBeEmpty()
    }
}
