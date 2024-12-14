package io.bluetape4k.workshop.exposed.dto

import java.io.Serializable

data class UserCreateRequest(
    val name: String,
    val age: Int,
): Serializable
