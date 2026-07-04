package io.bluetape4k.workshop.observability.advanced.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * Represents a user entity stored in the database.
 *
 * ## Behavior / Contract
 * - Implements [Serializable] for Redis cache serialization.
 */
data class User(
    val id: Long,
    val name: String,
    val email: String,
) : Serializable {
    init {
        id.requirePositiveNumber("id")
        name.requireNotBlank("name")
        email.requireNotBlank("email")
    }

    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
