package io.bluetape4k.workshop.messaging.kafka.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.messaging.kafka.KafkaApplication
import io.bluetape4k.workshop.messaging.kafka.listener.LoggerMessageHandler
import io.bluetape4k.workshop.messaging.kafka.model.GreetingRequest
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    classes = [KafkaApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class GreetingControllerTest(@Autowired private val client: WebTestClient) {

    companion object: KLogging()

    @Autowired
    private val loggerMessageHandler: LoggerMessageHandler = uninitialized()

    @Test
    fun `greeting to simple topic`() {
        client.get()
            .uri("/greeting?message=${"Hello, Kafka!"}")
            .exchange()
            .expectStatus().isAccepted
    }

    @Test
    fun `greeting to greeting topic and relay to logger topic`() {

        // GreetingRequest -> Greeting Topic -> Greeting Handler -> Logging Topic -> Logger Handler
        client.post()
            .uri("/greeting")
            .bodyValue(GreetingRequest("Debop"))
            .exchange()
            .expectStatus().isAccepted

        // Logger Topic 으로 전송된 메시지를 수신하는 것을 확인하기 위한 코드

        await until { loggerMessageHandler.receivedMessages.peek() != null }
    }
}
