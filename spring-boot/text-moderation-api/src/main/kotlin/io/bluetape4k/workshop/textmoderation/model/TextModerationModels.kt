package io.bluetape4k.workshop.textmoderation.model

import java.io.Serializable

/**
 * text moderation analysis 를 위한 JSON request 입니다.
 */
data class ModerationRequest(
    val text: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * API 가 반환하는 normalized text moderation response 입니다.
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
 * moderation request failure 에 대한 안정적인 error response 입니다.
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
