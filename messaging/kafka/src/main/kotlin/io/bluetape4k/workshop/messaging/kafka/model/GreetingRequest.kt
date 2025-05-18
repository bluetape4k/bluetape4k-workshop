package io.bluetape4k.workshop.messaging.kafka.model

import java.io.Serializable

data class GreetingRequest(
    val name: String,
): Serializable
