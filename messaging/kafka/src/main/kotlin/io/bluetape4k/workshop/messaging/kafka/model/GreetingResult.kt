package io.bluetape4k.workshop.messaging.kafka.model

import java.io.Serializable
import java.time.LocalDateTime

data class GreetingResult(
    val message: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
): Serializable
