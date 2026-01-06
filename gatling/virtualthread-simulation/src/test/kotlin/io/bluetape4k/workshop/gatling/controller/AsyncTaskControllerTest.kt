package io.bluetape4k.workshop.gatling.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.gatling.AbstractGatlingTest
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldNotBeNull
import org.springframework.test.web.reactive.server.returnResult
import kotlin.test.Test

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
            .get()
            .uri("/async/$seconds")
            .exchangeSuccessfully()
            .returnResult<String>().responseBody
            .awaitSingle()

        log.info { "Response: $response" }
    }
}
