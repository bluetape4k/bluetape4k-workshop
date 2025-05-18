package io.bluetape4k.workshop.elasticsearch.domain.dto

import io.bluetape4k.workshop.elasticsearch.domain.metadata.PublicationYear
import io.bluetape4k.workshop.elasticsearch.domain.model.Book
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.io.Serializable


data class ModifyBookRequest(
    @NotBlank
    val title: String,

    @Positive
    @PublicationYear
    val publicationYear: Int,

    @NotBlank
    val authorName: String,

    @NotBlank
    val isbn: String,
): Serializable

fun ModifyBookRequest.toBook(): Book {
    return Book(
        title = title,
        publicationYear = publicationYear,
        authorName = authorName,
        isbn = isbn,
    )
}

fun Book.toModifyBookRequest(): ModifyBookRequest {
    return ModifyBookRequest(
        title = title,
        publicationYear = publicationYear,
        authorName = authorName,
        isbn = isbn,
    )
}
