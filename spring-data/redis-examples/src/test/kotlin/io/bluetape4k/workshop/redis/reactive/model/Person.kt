package io.bluetape4k.workshop.redis.reactive.model

import java.io.Serializable

data class Person(
    val firstname: String = "",
    val lastname: String = "",
): Serializable
