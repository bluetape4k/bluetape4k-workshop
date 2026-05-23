package io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.io.Serializable

data class AuthorDTO(
    val id: Long = 0,
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class BookDTO(
    val id: Long = 0,
    val title: String = "",
    val publishDate: String = "",
    val authorId: Long = 0,
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class AuthorWithBooksDTO(
    val author: AuthorDTO,
    val books: List<BookDTO> = emptyList(),
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class CreateAuthorRequest(
    @field:NotBlank val firstName: String = "",
    @field:NotBlank val lastName: String = "",
    @field:Email val email: String = "",
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class CreateBookRequest(
    @field:NotBlank val title: String = "",
    val publishDate: String = "",
    @field:Positive val authorId: Long = 0,
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}
