package io.bluetape4k.workshop.textmoderation.config

import com.github.pemistahl.lingua.api.LanguageDetector
import io.bluetape4k.lingua.allLanguageDetector
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.ahoCorasick
import io.bluetape4k.tokenizer.utils.DictionaryVersion
import io.bluetape4k.workshop.textmoderation.service.VersionedModerationDictionary
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
            canonicalBlockwords(properties)
                .forEach { word -> keyword(word, word) }
        }

    @Bean
    fun versionedModerationDictionary(
        properties: TextModerationProperties,
        moderationAutomaton: AhoCorasickAutomaton<String>,
    ): VersionedModerationDictionary {
        val words = canonicalBlockwords(properties)
        return VersionedModerationDictionary.fromAutomaton(
            automaton = moderationAutomaton,
            version = DictionaryVersion("moderation-blockwords", 1),
            wordCount = words.size,
            totalCharacters = words.sumOf(String::length),
            historyCapacity = 2,
        )
    }

    private fun canonicalBlockwords(properties: TextModerationProperties): List<String> =
        properties.blockwords
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
}
