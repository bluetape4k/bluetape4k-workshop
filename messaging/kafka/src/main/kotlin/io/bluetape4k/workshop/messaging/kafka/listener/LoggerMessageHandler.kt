package io.bluetape4k.workshop.messaging.kafka.listener

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.messaging.kafka.KafkaTopics
import io.bluetape4k.workshop.messaging.kafka.model.GreetingResult
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Logger Topic 에서 [GreetingResult] 메시지를 수신하는 Kafka Message Handler
 *
 * @constructor Create empty Logger message handler
 */
@Component
class LoggerMessageHandler: KafkaMessageHandler<GreetingResult, Unit> {

    companion object: KLoggingChannel()

    val receivedMessages = ConcurrentLinkedDeque<GreetingResult>()

    /**
     * [GreetingResult] 메시지를 수신하면 로그를 출력한다.
     *
     * @param message 수신된 [GreetingResult] 메시지
     */
    @KafkaListener(topics = [KafkaTopics.TOPIC_LOGGER])
    override fun handle(message: GreetingResult) {
        log.info { "Received greeting result: $message" }
        receivedMessages.add(message)
    }
}
