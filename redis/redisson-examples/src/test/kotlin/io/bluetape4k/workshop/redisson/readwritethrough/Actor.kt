package io.bluetape4k.workshop.redisson.readwritethrough

import java.io.Serializable

data class Actor(
    val id: Int,
    val firstname: String,
    val lastname: String,
): Serializable
