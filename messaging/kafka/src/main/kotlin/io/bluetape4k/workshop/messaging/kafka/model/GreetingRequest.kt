package io.bluetape4k.workshop.messaging.kafka.model

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Request payload for greeting messages published to Kafka.
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
