package io.bluetape4k.workshop.kafka.ping


import io.bluetape4k.concurrent.onFailure
import io.bluetape4k.concurrent.onSuccess
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.uninitialized
import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate
import org.springframework.kafka.requestreply.RequestReplyFuture
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Sends a Kafka request/reply ping and returns the pong response.
 */
@RestController
class PingController {

    companion object : KLoggingChannel() {
        private const val TOPIC_PINGPONG = "pingpong"
        private const val PING_PAYLOAD = "ping"
    }

    @Autowired
    private val template: ReplyingKafkaTemplate<String, String, String> = uninitialized()

    @GetMapping("/ping")
    suspend fun ping(): String {
        log.info { "Sending ping ..." }
        val payload = PING_PAYLOAD.requireNotBlank("payload")
        val record = ProducerRecord<String, String>(TOPIC_PINGPONG, payload)
        val replyFuture: RequestReplyFuture<String, String, String> = template.sendAndReceive(record)
        replyFuture
            .onSuccess { result -> log.info { "callback result: $result" } }
            .onFailure { e -> log.error(e) { "callback exception." } }

        // 전송 결과
        val sendResult = replyFuture.sendFuture.requireNotNull("replyFuture.sendFuture").await()
        log.info { "Sent ok: $sendResult" }

        // 응답 결과
        val consumerRecord = replyFuture.await()
        log.info { "Return ok: key=${consumerRecord.key()}, value=${consumerRecord.value()}" }

        return consumerRecord.value()
    }
}
