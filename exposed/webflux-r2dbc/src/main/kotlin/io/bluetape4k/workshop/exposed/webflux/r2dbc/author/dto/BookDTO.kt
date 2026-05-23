package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto

import jakarta.validation.constraints.NotBlank
import java.io.Serializable

data class BookDTO(
    val id: Long = 0L,
    val title: String = "",
    val publishDate: String = "",
    val authorId: Long = 0L,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class CreateBookRequest(
    @field:NotBlank val title: String = "",
    @field:NotBlank val publishDate: String = "",
    val authorId: Long = 0L,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
