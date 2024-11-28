package io.bluetape4k.workshop.exposed.virtualthread.domain.dto

import java.io.Serializable

/**
 * 영화 배우 정보를 담는 DTO
 */
data class ActorDTO(
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String? = null,
    val id: Int? = null,
): Serializable
