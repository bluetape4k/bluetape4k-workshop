package io.bluetape4k.workshop.textmoderation.config

import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.text.search.NormalizationForm
import java.io.Serializable
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * text moderation API workshop module 의 configuration property 입니다.
 */
@ConfigurationProperties(prefix = "workshop.text-moderation")
data class TextModerationProperties(
    val maxTextCharacters: Int = 2_000,
    val blockwords: List<String> = listOf("spam", "badword", "abuse", "hate"),
    val normalization: NormalizationForm = NormalizationForm.NFC,
) : Serializable {
    init {
        maxTextCharacters.requirePositiveNumber("maxTextCharacters")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
