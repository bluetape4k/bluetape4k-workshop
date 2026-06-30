package io.bluetape4k.workshop.textmoderation.model

import java.io.Serializable

/**
 * JSON request for a text moderation analysis.
 */
data class ModerationRequest(
    val text: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Normalized text moderation response returned by the API.
 */
data class ModerationResponse(
    val detectedLanguage: String?,
    val confidence: Double?,
    val matchedTerms: List<String>,
    val maskedText: String,
    val warnings: List<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Stable error response for moderation request failures.
 */
data class ModerationErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
