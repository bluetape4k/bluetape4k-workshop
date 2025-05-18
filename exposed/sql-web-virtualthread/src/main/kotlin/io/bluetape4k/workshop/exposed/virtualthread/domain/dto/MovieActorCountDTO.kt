package io.bluetape4k.workshop.exposed.virtualthread.domain.dto

/**
 * 영화 제목과 영화에 출연한 배우의 수를 나타내는 DTO
 */
data class MovieActorCountDTO(
    val movieName: String,
    val actorCount: Int,
)
