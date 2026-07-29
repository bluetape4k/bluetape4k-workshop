package io.bluetape4k.workshop.textmoderation.config

import com.github.pemistahl.lingua.api.LanguageDetector
import io.bluetape4k.lingua.allLanguageDetector
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.ahoCorasick
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * moderation API 에서 재사용하는 text-analysis component 를 구성합니다.
 */
@Configuration(proxyBeanMethods = false)
class TextModerationConfig {

    @Bean
    fun languageDetector(): LanguageDetector =
        allLanguageDetector {
            withMinimumRelativeDistance(0.0)
            withLowAccuracyMode()
        }

    @Bean
    fun moderationAutomaton(properties: TextModerationProperties): AhoCorasickAutomaton<String> =
        ahoCorasick {
            ignoreCase = true
            allowOverlaps = true
            normalization = NormalizationForm.NFC
            properties.blockwords
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { word -> keyword(word, word) }
        }
}
