package io.bluetape4k.workshop.textmoderation.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.ahoCorasick
import io.bluetape4k.tokenizer.utils.DictionarySnapshot
import io.bluetape4k.tokenizer.utils.DictionaryVersion
import io.bluetape4k.tokenizer.utils.VersionedDictionary
import io.bluetape4k.workshop.textmoderation.model.ModerationResponse
import java.io.Serializable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** moderation dictionary가 허용하는 bounded input입니다. */
data class ModerationDictionaryLimits(
    val maxWords: Int = 10_000,
    val maxWordCharacters: Int = 200,
    val maxTotalCharacters: Int = 100_000,
) {
    init {
        require(maxWords > 0) { "maxWords must be positive" }
        require(maxWordCharacters > 0) { "maxWordCharacters must be positive" }
        require(maxTotalCharacters > 0) { "maxTotalCharacters must be positive" }
    }
}

/** raw blockword를 노출하지 않는 현재 dictionary metadata입니다. */
data class ModerationDictionaryMetadata(
    val version: DictionaryVersion,
    val wordCount: Int,
    val totalCharacters: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 요청에 사용한 dictionary metadata와 기존 moderation response를 함께 반환합니다. */
data class VersionedModerationResult(
    val dictionary: ModerationDictionaryMetadata,
    val response: ModerationResponse,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 외부에 collection을 노출하지 않는 완성된 moderation matcher snapshot입니다. */
internal data class ModerationDictionaryValue(
    val automaton: AhoCorasickAutomaton<String>,
    val wordCount: Int,
    val totalCharacters: Int,
)

/**
 * blockword collection을 완성된 immutable automaton으로 만든 뒤 원자적으로 교체합니다.
 *
 * loader, validation, automaton build는 mutation lock 밖에서 수행됩니다. 공개 API는 raw
 * blockword 대신 revision과 크기 metadata만 반환합니다.
 */
class VersionedModerationDictionary private constructor(
    initial: DictionarySnapshot<ModerationDictionaryValue>,
    historyCapacity: Int,
    private val limits: ModerationDictionaryLimits,
) {

    private val versions = VersionedDictionary(initial, historyCapacity)
    private val mutationLock = ReentrantLock()

    init {
        require(initial.version.name == COMPATIBILITY_DICTIONARY_NAME) {
            "Expected $COMPATIBILITY_DICTIONARY_NAME version"
        }
    }

    @JvmOverloads
    constructor(
        initialVersion: DictionaryVersion,
        initialWords: Collection<String>,
        historyCapacity: Int = 1,
        limits: ModerationDictionaryLimits = ModerationDictionaryLimits(),
    ) : this(
        initial = initialSnapshot(initialVersion, initialWords, limits),
        historyCapacity = historyCapacity,
        limits = limits,
    )

    /** 현재 revision과 bounded size metadata를 반환합니다. */
    fun currentMetadata(): ModerationDictionaryMetadata = versions.snapshot().toMetadata()

    /** caller collection을 복사하고 검증해 새 dictionary revision으로 공개합니다. */
    fun reload(
        version: DictionaryVersion,
        words: Collection<String>,
    ): ModerationDictionaryMetadata = reload(version) { words }

    /** loader와 candidate build가 끝난 뒤 완성된 snapshot만 원자적으로 공개합니다. */
    fun reload(
        version: DictionaryVersion,
        loader: () -> Collection<String>,
    ): ModerationDictionaryMetadata {
        require(version.name == COMPATIBILITY_DICTIONARY_NAME) {
            "Expected $COMPATIBILITY_DICTIONARY_NAME version"
        }
        val candidate = buildValue(loader(), limits)
        return mutationLock.withLock {
            val previous = versions.snapshot()
            val updated = versions.reload(DictionarySnapshot(version, candidate))
            updated.toMetadata().also { metadata ->
                log.info {
                    "dictionary operation=reload previousRevision=${previous.version.revision}, " +
                            "revision=${metadata.version.revision}, wordCount=${metadata.wordCount}, " +
                            "totalCharacters=${metadata.totalCharacters}"
                }
            }
        }
    }

    /** bounded history의 가장 최근 dictionary revision으로 되돌아갑니다. */
    fun rollback(): ModerationDictionaryMetadata = mutationLock.withLock {
        val previous = versions.snapshot()
        versions.rollback().toMetadata().also { metadata ->
            log.info {
                "dictionary operation=rollback previousRevision=${previous.version.revision}, " +
                        "revision=${metadata.version.revision}, wordCount=${metadata.wordCount}, " +
                        "totalCharacters=${metadata.totalCharacters}"
            }
        }
    }

    /** 한 요청이 parse와 mask에 재사용할 내부 immutable snapshot입니다. */
    internal fun snapshot(): DictionarySnapshot<ModerationDictionaryValue> = versions.snapshot()

    companion object : KLogging() {
        private const val COMPATIBILITY_DICTIONARY_NAME = "moderation-blockwords"

        private fun initialSnapshot(
            version: DictionaryVersion,
            words: Collection<String>,
            limits: ModerationDictionaryLimits,
        ): DictionarySnapshot<ModerationDictionaryValue> {
            require(version.name == COMPATIBILITY_DICTIONARY_NAME) {
                "Expected $COMPATIBILITY_DICTIONARY_NAME version"
            }
            return DictionarySnapshot(version, buildValue(words, limits))
        }

        internal fun fromAutomaton(
            automaton: AhoCorasickAutomaton<String>,
            words: Collection<String>,
            version: DictionaryVersion = DictionaryVersion(COMPATIBILITY_DICTIONARY_NAME, 0),
            historyCapacity: Int = 1,
            limits: ModerationDictionaryLimits = ModerationDictionaryLimits(),
        ): VersionedModerationDictionary {
            val canonicalWords = canonicalizeWords(words, limits)
            return fromAutomaton(
                automaton = automaton,
                version = version,
                wordCount = canonicalWords.size,
                totalCharacters = canonicalWords.sumOf(String::length),
                historyCapacity = historyCapacity,
                limits = limits,
            )
        }

        internal fun fromAutomaton(
            automaton: AhoCorasickAutomaton<String>,
            version: DictionaryVersion = DictionaryVersion(COMPATIBILITY_DICTIONARY_NAME, 0),
            wordCount: Int = 0,
            totalCharacters: Int = 0,
            historyCapacity: Int = 1,
            limits: ModerationDictionaryLimits = ModerationDictionaryLimits(),
        ): VersionedModerationDictionary {
            require(wordCount >= 0) { "wordCount must not be negative" }
            require(totalCharacters >= 0) { "totalCharacters must not be negative" }
            return VersionedModerationDictionary(
                initial = DictionarySnapshot(
                    version,
                    ModerationDictionaryValue(automaton, wordCount, totalCharacters),
                ),
                historyCapacity = historyCapacity,
                limits = limits,
            )
        }

        private fun buildValue(
            words: Collection<String>,
            limits: ModerationDictionaryLimits,
        ): ModerationDictionaryValue {
            val canonicalWords = canonicalizeWords(words, limits)
            val totalCharacters = canonicalWords.sumOf(String::length)

            val automaton = ahoCorasick<String> {
                ignoreCase = true
                allowOverlaps = true
                normalization = NormalizationForm.NFC
                canonicalWords.forEach { word -> keyword(word, word) }
            }
            return ModerationDictionaryValue(
                automaton = automaton,
                wordCount = canonicalWords.size,
                totalCharacters = totalCharacters,
            )
        }

        private fun canonicalizeWords(
            words: Collection<String>,
            limits: ModerationDictionaryLimits,
        ): Set<String> {
            val canonicalWords = linkedSetOf<String>()
            var sourceWordCount = 0
            var totalCharacters = 0L
            for (rawWord in words) {
                sourceWordCount++
                require(sourceWordCount <= limits.maxWords) {
                    "dictionary word count exceeds ${limits.maxWords}"
                }
                val word = rawWord.trim()
                if (word.isEmpty()) continue
                require(word.length <= limits.maxWordCharacters) {
                    "dictionary word length exceeds ${limits.maxWordCharacters}"
                }
                if (canonicalWords.add(word)) {
                    totalCharacters += word.length
                    require(totalCharacters <= limits.maxTotalCharacters) {
                        "dictionary total characters exceed ${limits.maxTotalCharacters}"
                    }
                }
            }
            return canonicalWords
        }

        private fun DictionarySnapshot<ModerationDictionaryValue>.toMetadata(): ModerationDictionaryMetadata =
            ModerationDictionaryMetadata(
                version = version,
                wordCount = value.wordCount,
                totalCharacters = value.totalCharacters,
            )
    }
}
