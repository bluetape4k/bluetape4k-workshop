package io.bluetape4k.workshop.messaging.kafka.model

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Greeting response emitted by Kafka greeting handlers.
 */
data class GreetingResult(
    val message: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
) : Serializable {

    init {
        message.requireNotBlank("message")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
