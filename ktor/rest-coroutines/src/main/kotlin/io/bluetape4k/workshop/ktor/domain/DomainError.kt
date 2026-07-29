package io.bluetape4k.workshop.ktor.domain

/**
 * book catalog 를 위한 domain-specific exception hierarchy 입니다.
 *
 * ## Behavior / Contract
 * - [NotFound] 는 `ApplicationModule` 의 StatusPages 를 통해 HTTP 404 로 매핑됩니다.
 * - [Conflict] 는 `ApplicationModule` 의 StatusPages 를 통해 HTTP 409 로 매핑됩니다.
 */
sealed class DomainError(message: String) : RuntimeException(message) {
    /** 주어진 id 의 book 이 없을 때 throw 됩니다. HTTP 404 로 매핑됩니다. */
    class NotFound(id: String) : DomainError("Book not found: id=$id")

    /** 같은 id 의 book 이 이미 있을 때 throw 됩니다. HTTP 409 로 매핑됩니다. */
    class Conflict(message: String) : DomainError(message)
}
