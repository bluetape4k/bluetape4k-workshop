package io.bluetape4k.workshop.messaging.kafka.model

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Kafka 로 publish 할 greeting message 의 request payload 입니다.
 */
data class GreetingRequest(
    val name: String,
) : Serializable {

    init {
        name.requireNotBlank("name")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
