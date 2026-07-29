package io.bluetape4k.workshop.kafka.pong

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * ping request 를 처리하고 Kafka reply message 를 전송합니다.
 */
@Component
class PongHandler {

    companion object : KLoggingChannel()

    @KafkaListener(groupId = "pong", topics = [PongApplication.TOPIC_PINGPONG])
    @SendTo // use default replyTo expression
    fun handle(request: String): String {
        val message = request.requireNotBlank("request")
        log.info { "Received: $message in ${this.javaClass.name}" }
        return "pong at ${LocalDateTime.now()}"
    }
}
