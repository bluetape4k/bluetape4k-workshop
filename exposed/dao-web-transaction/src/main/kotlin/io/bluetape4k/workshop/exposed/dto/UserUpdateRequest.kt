package io.bluetape4k.workshop.exposed.dto

import java.io.Serializable

data class UserUpdateRequest(
    val name: String? = null,
    val age: Int? = null,
): Serializable
