package io.bluetape4k.workshop.ktor.exposedrest

import io.bluetape4k.support.requireNotBlank
import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

@Serializable
internal data class BookRequest(
    val title: String,
    val author: String,
    val isbn: String,
): JavaSerializable {

    fun validated(): BookRequest =
        copy(
            title = title.requireNotBlank("title").trim(),
            author = author.requireNotBlank("author").trim(),
            isbn = isbn.requireNotBlank("isbn").trim(),
        )

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Serializable
internal data class BookResponse(
    val id: Long,
    val title: String,
    val author: String,
    val isbn: String,
): JavaSerializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
