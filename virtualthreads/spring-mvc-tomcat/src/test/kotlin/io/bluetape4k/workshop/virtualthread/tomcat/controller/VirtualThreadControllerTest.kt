package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class VirtualThreadControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractVirtualThreadMvcTest() {

    companion object: KLoggingChannel()

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
    fun `run multiple tasks with StructuredTaskScope`() = runSuspendIO {
        val response = client
            .httpGet("/virtual-thread/multi")
            .returnResult<String>().responseBody
            .awaitSingle()

        log.debug { "Response: $response" }
        response.shouldNotBeEmpty()
    }

    @Test
    fun `run multiple tasks with virtualFutureAll`() = runSuspendIO {
        val response = client
            .httpGet("/virtual-thread/virtualFutureAll")
            .returnResult<String>().responseBody
            .awaitSingle()

        log.debug { "Response: $response" }
        response.shouldNotBeEmpty()
    }
}
