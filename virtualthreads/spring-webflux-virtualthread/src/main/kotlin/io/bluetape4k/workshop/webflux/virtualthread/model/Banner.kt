package io.bluetape4k.workshop.webflux.virtualthread.model

import java.io.Serializable
import java.time.Instant

data class Banner(
    val title: String,
    val content: String,
    val createdAt: Instant = Instant.now(),
): Serializable
