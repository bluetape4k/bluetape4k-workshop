package io.bluetape4k.workshop.application.event.custom

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.junit5.output.OutputCapture
import io.bluetape4k.junit5.output.OutputCapturer
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.spring.tests.httpGet
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

@OutputCapture
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomEventPublisherTest(@param:Autowired private val client: WebTestClient) {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Test
    fun `publish custom event`(output: OutputCapturer) = runSuspendIO {
        val response = client.httpGet("/event?message=CustomEventMessage")
            .returnResult<String>().responseBody.awaitSingle()

        response shouldBeEqualTo "Finished"

        delay(100L)
        output.capture() shouldContain "Handle custom event"
    }
}
