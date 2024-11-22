package io.bluetape4k.workshop.asynclogging.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.asynclogging.AbstractAsyncLoggerTest
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient

class LoggingControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractAsyncLoggerTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Test
    fun `call trace`() = runTest {
        callLoggerApi("trace")
    }

    @Test
    fun `call debug`() = runTest {
        callLoggerApi("debug")
    }

    @Test
    fun `call info`() = runTest {
        callLoggerApi("info")
    }

    @Test
    fun `call warn`() = runTest {
        callLoggerApi("warn")
    }

    @Test
    fun `call error`() = runTest {
        callLoggerApi("error")
    }

    private suspend fun callLoggerApi(path: String) {
        client.get()
            .uri("/$path")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .consumeWith {
                log.info { "response: ${it.responseBody?.toString(Charsets.UTF_8)}" }
            }
    }
}
