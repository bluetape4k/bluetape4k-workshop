package io.bluetape4k.workshop.exposed.dto

import io.bluetape4k.workshop.exposed.domain.UserId
import java.io.Serializable

data class UserCreateResponse(
    val id: UserId,
): Serializable
