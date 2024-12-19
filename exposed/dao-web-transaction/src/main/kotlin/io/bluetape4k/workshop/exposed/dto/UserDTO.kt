package io.bluetape4k.workshop.exposed.dto

import java.io.Serializable

data class UserDTO(
    val id: Long,
    val name: String,
    val age: Int,
): Serializable
