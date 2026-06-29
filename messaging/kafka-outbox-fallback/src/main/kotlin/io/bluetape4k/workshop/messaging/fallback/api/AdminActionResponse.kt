package io.bluetape4k.workshop.messaging.fallback.api

import java.io.Serializable

/**
 * Small response returned by opt-in demo admin actions.
 */
data class AdminActionResponse(
    val action: String,
    val claimed: Int = 0,
    val published: Int = 0,
    val failed: Int = 0,
    val deadLettered: Int = 0,
    val scanned: Int = 0,
    val reconstructed: Int = 0,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
