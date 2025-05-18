package io.bluetape4k.workshop.gateway.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.gateway.AbstractGatewayTest
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class IndexControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractGatewayTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Test
    fun `hello endpoint`() = runSuspendIO {
        client.httpGet("/hello")
            .returnResult<String>().responseBody
            .awaitSingle() shouldBeEqualTo "Hello Bluetape4k from API Gateway"

        client.httpGet("/hello?name=Debop")
            .returnResult<String>().responseBody
            .awaitSingle() shouldBeEqualTo "Hello Debop from API Gateway"
    }
}
