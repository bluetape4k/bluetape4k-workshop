package io.bluetape4k.workshop.messaging.kafka.listener

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.messaging.kafka.KafkaTopics
import io.bluetape4k.workshop.messaging.kafka.model.GreetingRequest
import io.bluetape4k.workshop.messaging.kafka.model.GreetingResult
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Greeting Topic에서 [GreetingRequest] 메시지를 수신하고,
 * Logger Topic 으로 [GreetingResult] 메시지를 전송하는 Kafka Message Handler
 *
 * @constructor Create empty Greeting message handler
 */
@Component
class GreetingMessageHandler: KafkaMessageHandler<GreetingRequest, GreetingResult> {

    companion object: KLoggingChannel()

    @KafkaListener(topics = [KafkaTopics.TOPIC_GREETING])
    @SendTo(KafkaTopics.TOPIC_LOGGER)
    override fun handle(message: GreetingRequest): GreetingResult {
        log.info { "Received message: $message" }
        return GreetingResult("Hello ${message.name}", LocalDateTime.now())
    }
}
