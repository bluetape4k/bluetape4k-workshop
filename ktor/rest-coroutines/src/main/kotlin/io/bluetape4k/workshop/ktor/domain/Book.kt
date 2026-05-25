package io.bluetape4k.workshop.ktor.domain

import kotlinx.serialization.Serializable

/**
 * Represents a book in the catalog.
 *
 * ## Behavior / Contract
 * - `@Serializable` (kotlinx) enables compile-time JSON serialization via ContentNegotiation and SSE encoding.
 *   This is independent of Jackson 3 serialization used for NDJSON export.
 * - Implements `java.io.Serializable` (workspace convention for all data classes).
 * - Pure value object; no database annotations.
 *
 * ```kotlin
 * val book = Book(id = "b-1", title = "Kotlin in Action", author = "Jemerov", year = 2017)
 * ```
 */
@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val year: Int,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
