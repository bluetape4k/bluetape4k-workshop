package io.bluetape4k.workshop.gatling.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.gatling.AbstractGatlingTest
import org.amshove.kluent.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import kotlin.test.Test

class AsyncTaskControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractGatlingTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Test
    fun `delay asynchronously`() {
        val seconds = 1

        client.httpGet("/async/$seconds")
            .expectBody<Long>()
            .consumeWith {
                log.info { "Response: ${it.responseBody}" }
            }
    }
}
