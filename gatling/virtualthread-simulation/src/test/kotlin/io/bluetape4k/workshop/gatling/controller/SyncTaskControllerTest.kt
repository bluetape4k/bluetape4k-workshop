package io.bluetape4k.workshop.gatling.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.gatling.AbstractGatlingTest
import io.bluetape4k.workshop.shared.web.httpGet
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldNotBeNull
import org.springframework.test.web.reactive.server.returnResult
import kotlin.test.Test

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
    }
}
