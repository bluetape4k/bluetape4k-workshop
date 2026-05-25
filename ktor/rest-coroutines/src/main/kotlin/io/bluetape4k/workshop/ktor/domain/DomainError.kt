package io.bluetape4k.workshop.ktor.domain

/**
 * Domain-specific exception hierarchy for the book catalog.
 *
 * ## Behavior / Contract
 * - [NotFound] maps to HTTP 404 via StatusPages in `ApplicationModule`.
 * - [Conflict] maps to HTTP 409 via StatusPages in `ApplicationModule`.
 */
sealed class DomainError(message: String) : RuntimeException(message) {
    /** Thrown when a book with the given id does not exist. Maps to HTTP 404. */
    class NotFound(id: String) : DomainError("Book not found: id=$id")

    /** Thrown when a book with the same id already exists. Maps to HTTP 409. */
    class Conflict(message: String) : DomainError(message)
}
