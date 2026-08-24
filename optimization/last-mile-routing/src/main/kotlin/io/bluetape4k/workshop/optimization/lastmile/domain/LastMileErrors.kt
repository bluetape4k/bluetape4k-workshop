package io.bluetape4k.workshop.optimization.lastmile.domain

class InvalidLastMileInput(message: String) : IllegalArgumentException(message)

enum class LastMileFailureCode {
    MATRIX_MISS,
    PROVIDER_UNAVAILABLE,
    STALE_ROUTE_APPROVAL,
    DUPLICATE_CALLBACK,
    DIGEST_CONFLICT,
}
