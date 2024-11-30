package io.bluetape4k.workshop.messaging.kafka.listener

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.messaging.kafka.KafkaTopics
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Simple Topic 에서 문자열 메시지를 수신하는 Kafka Message Handler
 */
@Component
class SimpleMessageHandler: KafkaMessageHandler<String, Unit> {

    companion object: KLogging()

    @KafkaListener(groupId = "simple", topics = [KafkaTopics.TOPIC_SIMPLE])
    override fun handle(message: String) {
        log.debug { "Received message: $message" }
    }
}
