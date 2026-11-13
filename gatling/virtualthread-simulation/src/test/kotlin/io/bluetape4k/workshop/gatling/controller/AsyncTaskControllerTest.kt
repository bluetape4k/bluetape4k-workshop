package io.bluetape4k.workshop.gatling.controller

import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInRange
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.gatling.AbstractGatlingTest
import io.bluetape4k.workshop.gatling.validation.MAX_DELAY_SECONDS
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult
import kotlin.system.measureTimeMillis

class AsyncTaskControllerTest: AbstractGatlingTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5
    }

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `delay asynchronously`() = runSuspendIO {
        val seconds = 5

        val elapsed = measureTimeMillis {
            val response = client
                .httpGet("/async/$seconds")
                .expectStatus().is2xxSuccessful
                .returnResult<Long>().responseBody
                .awaitSingle()

            log.info { "delay time: $response msec" }
            response shouldBeGreaterOrEqualTo 0L
        }
        log.info { "delay asynchronously: $elapsed ms" }
        elapsed shouldBeInRange 0L..(seconds * 1_000L)
    }

    @Test
    fun `reject invalid delay seconds`() {
        client.httpGet("/async/0")
            .expectStatus().isBadRequest

        client.httpGet("/async/${MAX_DELAY_SECONDS + 1}")
            .expectStatus().isBadRequest
    }
}
