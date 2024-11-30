package io.bluetape4k.workshop.messaging.kafka.listener

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.messaging.kafka.KafkaTopics
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.mono
import org.springframework.kafka.annotation.KafkaListener
import reactor.core.publisher.Mono

/**
 * NOTE: @KafkaListener는 Coroutines, Reactive 방식은 지원하지 않습니다.
 */
// @Component
class CoroutineSimpleMessageHandler {

    companion object: KLogging()

    @KafkaListener(groupId = "coroutine-simple", topics = [KafkaTopics.TOPIC_SIMPLE])
    suspend fun handleWithCoroutines(message: String) {
        log.debug { "Received message in coroutines: $message" }
        delay(100)

    }

    @KafkaListener(groupId = "reactive-simple", topics = [KafkaTopics.TOPIC_SIMPLE])
    fun handleWithMono(message: String): Mono<Void> = mono {
        log.debug { "Received message in reactor: $message" }
        delay(100)
        null
    }
}
