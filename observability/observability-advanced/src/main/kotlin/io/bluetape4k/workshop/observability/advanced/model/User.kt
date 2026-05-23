package io.bluetape4k.workshop.observability.advanced.model

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
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
