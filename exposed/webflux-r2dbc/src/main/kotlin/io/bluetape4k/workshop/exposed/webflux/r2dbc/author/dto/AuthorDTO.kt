package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.io.Serializable

data class AuthorDTO(
    val id: Long = 0L,
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class CreateAuthorRequest(
    @field:NotBlank val firstName: String = "",
    @field:NotBlank val lastName: String = "",
    @field:Email @field:NotBlank val email: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
