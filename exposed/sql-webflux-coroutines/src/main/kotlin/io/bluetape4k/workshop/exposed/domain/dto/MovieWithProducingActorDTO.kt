package io.bluetape4k.workshop.exposed.domain.dto

/**
 * 영화 제목과 영화를 제작한 배우의 이름을 나타내는 DTO
 */
data class MovieWithProducingActorDTO(
    val movieName: String,
    val producerActorName: String,
)
