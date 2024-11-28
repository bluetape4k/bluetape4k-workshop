package io.bluetape4k.workshop.exposed.virtualthread.domain.dto

import java.io.Serializable

/**
 * 영화 정보를 나타내는 DTO
 */
data class MovieDTO(
    val name: String,
    val producerName: String,
    val releaseDate: String,
    val id: Int? = null,
): Serializable
