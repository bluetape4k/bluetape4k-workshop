package io.bluetape4k.workshop.messaging.kafka.model

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Kafka greeting handler 가 방출하는 greeting response 입니다.
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
