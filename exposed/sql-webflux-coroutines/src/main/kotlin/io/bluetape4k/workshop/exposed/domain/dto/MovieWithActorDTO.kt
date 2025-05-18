package io.bluetape4k.workshop.exposed.domain.dto

import java.io.Serializable

/**
 * 영화 정보와 해당 영화에 출연한 배우 정보를 나타내는 DTO
 */
data class MovieWithActorDTO(
    val name: String,
    val producerName: String,
    val releaseDate: String,
    val actors: MutableList<ActorDTO> = mutableListOf(),
    val id: Int? = null,
): Serializable
