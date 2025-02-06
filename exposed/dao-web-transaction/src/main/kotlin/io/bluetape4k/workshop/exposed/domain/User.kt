package io.bluetape4k.workshop.exposed.domain

import java.io.Serializable

@JvmInline
value class UserId(val value: Long): Serializable

data class User(
    val id: UserId,
    val name: String,
    val age: Int,
): Serializable
