package io.bluetape4k.workshop.gateway.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.gateway.AbstractGatewayTest
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class IndexControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractGatewayTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Test
    fun `hello endpoint`() = runTest {
        client.get()
            .uri("/hello")
            .exchange()
            .expectStatus().isOk
            .returnResult<String>().responseBody
            .awaitSingle() shouldBeEqualTo "Hello Bluetape4k from API Gateway"

        client.get()
            .uri("/hello?name=Debop")
            .exchange()
            .expectStatus().isOk
            .returnResult<String>().responseBody
            .awaitSingle() shouldBeEqualTo "Hello Debop from API Gateway"

    }
}
