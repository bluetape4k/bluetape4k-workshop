package io.bluetape4k.workshop.gatling.controller

import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.gatling.AbstractGatlingTest
import io.bluetape4k.workshop.gatling.validation.MAX_DELAY_SECONDS
import io.bluetape4k.spring.tests.httpGet
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class AsyncTaskControllerTest: AbstractGatlingTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Test
    fun `delay asynchronously`() = runSuspendIO {
        val seconds = 1

        val response = client
            .httpGet("/async/$seconds")
            .expectStatus().is2xxSuccessful
            .returnResult<Long>().responseBody
            .awaitSingle()

        log.info { "delay time: $response msec" }
        response shouldBeGreaterThan 0L
    }

    @Test
    fun `reject invalid delay seconds`() {
        client.httpGet("/async/0")
            .expectStatus().isBadRequest

        client.httpGet("/async/${MAX_DELAY_SECONDS + 1}")
            .expectStatus().isBadRequest
    }
}
