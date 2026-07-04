package io.bluetape4k.workshop.gatling.controller

import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.gatling.AbstractGatlingTest
import io.bluetape4k.workshop.gatling.validation.MAX_DELAY_SECONDS
import io.bluetape4k.workshop.shared.web.httpGet
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class SyncTaskControllerTest: AbstractGatlingTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Test
    fun `delay synchronously`() = runSuspendIO {
        val seconds = 1

        val response = client
            .httpGet("/sync/$seconds")
            .expectStatus().is2xxSuccessful
            .returnResult<Long>().responseBody
            .awaitSingle()

        log.info { "delay time: $response msec" }
        response shouldBeGreaterThan 0L
    }

    @Test
    fun `reject invalid delay seconds`() {
        client.httpGet("/sync/0")
            .expectStatus().isBadRequest

        client.httpGet("/sync/${MAX_DELAY_SECONDS + 1}")
            .expectStatus().isBadRequest
    }
}
