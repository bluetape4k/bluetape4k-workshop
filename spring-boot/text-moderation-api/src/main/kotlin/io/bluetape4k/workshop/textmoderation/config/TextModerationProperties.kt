package io.bluetape4k.workshop.textmoderation.config

import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the text moderation API workshop module.
 */
@ConfigurationProperties(prefix = "workshop.text-moderation")
data class TextModerationProperties(
    val maxTextCharacters: Int = 2_000,
    val blockwords: List<String> = listOf("spam", "badword", "abuse", "hate"),
) : Serializable {
    init {
        maxTextCharacters.requirePositiveNumber("maxTextCharacters")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
