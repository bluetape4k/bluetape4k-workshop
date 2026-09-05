package io.bluetape4k.workshop.text.search

import com.github.pemistahl.lingua.api.Language
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.ahoCorasick
import io.bluetape4k.tokenizer.korean.utils.KoreanPos
import io.bluetape4k.tokenizer.utils.DictionarySnapshot
import io.bluetape4k.tokenizer.utils.DictionaryVersion
import io.bluetape4k.tokenizer.utils.VersionedDictionary
import io.bluetape4k.workshop.text.detection.LanguageDetectionService
import io.bluetape4k.workshop.text.normalize.TextNormalizer
import java.io.Serializable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 검색에 사용한 Korean dictionary revision과 ranked hit를 함께 반환합니다. */
data class VersionedSearchResult(
    val version: DictionaryVersion,
    val hits: List<SearchHighlightHit>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Korean noun snapshot과 그 snapshot으로 다시 만들 document collection을 묶습니다. */
data class VersionedMultilingualSearchSource(
    val koreanDictionary: DictionarySnapshot<Map<KoreanPos, Set<String>>>,
    val documents: Collection<SearchDocument>,
)

/** Versioned search candidate의 bounded input 계약입니다. */
data class VersionedSearchLimits(
    val maxDocuments: Int = 10_000,
    val maxDocumentCharacters: Int = 100_000,
    val maxTotalDocumentCharacters: Int = 1_000_000,
    val maxKoreanNouns: Int = 500_000,
    val maxKoreanNounCharacters: Int = 100,
    val maxTotalKoreanNounCharacters: Int = 2_000_000,
) {
    init {
        require(maxDocuments > 0) { "maxDocuments must be positive" }
        require(maxDocumentCharacters > 0) { "maxDocumentCharacters must be positive" }
        require(maxTotalDocumentCharacters > 0) { "maxTotalDocumentCharacters must be positive" }
        require(maxKoreanNouns > 0) { "maxKoreanNouns must be positive" }
        require(maxKoreanNounCharacters > 0) { "maxKoreanNounCharacters must be positive" }
        require(maxTotalKoreanNounCharacters > 0) { "maxTotalKoreanNounCharacters must be positive" }
    }
}

private data class VersionedSearchGeneration(
    val index: MultilingualSearchIndex,
    val koreanNounCount: Int,
)

private data class PreparedSearchGeneration(
    val version: DictionaryVersion,
    val generation: VersionedSearchGeneration,
)

/**
 * Korean noun snapshot과 완성된 [MultilingualSearchIndex] generation을 함께 원자 교체합니다.
 *
 * 기존 search index의 global Korean processor 대신 snapshot 전용 exact-noun matcher를 document와
 * query 양쪽에 주입합니다. 따라서 upstream provider가 별도로 바뀌어도 공개된 generation은 영향을
 * 받지 않습니다. Japanese/English 경로는 기존 tokenizer 계약을 유지합니다.
 */
class VersionedMultilingualSearchIndex private constructor(
    private val versions: VersionedDictionary<VersionedSearchGeneration>,
    private val detectionService: LanguageDetectionService,
    private val limits: VersionedSearchLimits,
) {
    private val mutationLock = ReentrantLock()

    /** 현재 검색 generation이 고정한 Korean dictionary 버전을 반환합니다. */
    fun currentVersion(): DictionaryVersion = versions.snapshot().version

    /** 요청 시작 시 고정한 generation으로 검색하고 사용한 버전을 함께 반환합니다. */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): VersionedSearchResult {
        val current = versions.snapshot()
        return VersionedSearchResult(
            version = current.version,
            hits = current.value.index.search(query, limit),
        )
    }

    /** 이미 읽은 Korean dictionary snapshot과 document collection으로 새 generation을 준비합니다. */
    fun reload(source: VersionedMultilingualSearchSource): DictionaryVersion = reload { source }

    /**
     * loader와 전체 generation build가 끝난 뒤 완성된 snapshot만 공개합니다.
     * 실패와 stale revision은 현재 generation과 bounded history를 변경하지 않습니다.
     */
    fun reload(loader: () -> VersionedMultilingualSearchSource): DictionaryVersion {
        val candidate = prepareGeneration(loader(), detectionService, limits)
        return mutationLock.withLock {
            val previous = versions.snapshot()
            val updated = versions.reload(DictionarySnapshot(candidate.version, candidate.generation))
            log.info {
                "search dictionary operation=reload previousRevision=${previous.version.revision}, " +
                        "revision=${updated.version.revision}, koreanNounCount=${updated.value.koreanNounCount}"
            }
            updated.version
        }
    }

    /** bounded history의 가장 최근 complete generation으로 되돌아갑니다. */
    fun rollback(): DictionaryVersion = mutationLock.withLock {
        val previous = versions.snapshot()
        val updated = versions.rollback()
        log.info {
            "search dictionary operation=rollback previousRevision=${previous.version.revision}, " +
                    "revision=${updated.version.revision}, koreanNounCount=${updated.value.koreanNounCount}"
        }
        updated.version
    }

    companion object: KLogging() {
        private const val DEFAULT_LIMIT = 10
        private const val KOREAN_DICTIONARY_NAME = "korean-dictionary"

        /** 최초 Korean dictionary snapshot과 whole-index generation을 생성합니다. */
        @JvmStatic
        @JvmOverloads
        fun indexOf(
            source: VersionedMultilingualSearchSource,
            historyCapacity: Int = 1,
            detectionService: LanguageDetectionService = LanguageDetectionService(),
            limits: VersionedSearchLimits = VersionedSearchLimits(),
        ): VersionedMultilingualSearchIndex {
            val initial = prepareGeneration(source, detectionService, limits)
            return VersionedMultilingualSearchIndex(
                versions = VersionedDictionary(
                    initial = DictionarySnapshot(initial.version, initial.generation),
                    historyCapacity = historyCapacity,
                ),
                detectionService = detectionService,
                limits = limits,
            )
        }

        private fun prepareGeneration(
            source: VersionedMultilingualSearchSource,
            detectionService: LanguageDetectionService,
            limits: VersionedSearchLimits,
        ): PreparedSearchGeneration {
            require(source.koreanDictionary.version.name == KOREAN_DICTIONARY_NAME) {
                "Expected $KOREAN_DICTIONARY_NAME version"
            }
            val documents = source.documents.toBoundedSnapshot(limits)
            val nouns = source.koreanDictionary.value[KoreanPos.Noun]
                .orEmpty()
                .toBoundedNounSnapshot(limits)
                .mapNotNull { noun -> noun.toSearchTermOrNull() }
                .toCollection(linkedSetOf())
            require(nouns.isNotEmpty()) { "Korean noun snapshot must not be empty" }

            val tokenizer = SnapshotKoreanNounTokenizer(nouns)
            val index = MultilingualSearchIndex.indexOf(documents, detectionService, tokenizer)
            return PreparedSearchGeneration(
                version = source.koreanDictionary.version,
                generation = VersionedSearchGeneration(index, nouns.size),
            )
        }
    }
}

private fun Collection<SearchDocument>.toBoundedSnapshot(limits: VersionedSearchLimits): List<SearchDocument> {
    val snapshot = ArrayList<SearchDocument>(minOf(size, limits.maxDocuments))
    var totalCharacters = 0L
    for (document in this) {
        require(snapshot.size < limits.maxDocuments) {
            "document count exceeds ${limits.maxDocuments}"
        }
        require(document.text.length <= limits.maxDocumentCharacters) {
            "document text length exceeds ${limits.maxDocumentCharacters}"
        }
        totalCharacters += document.text.length
        require(totalCharacters <= limits.maxTotalDocumentCharacters) {
            "document total characters exceed ${limits.maxTotalDocumentCharacters}"
        }
        snapshot += document
    }
    return snapshot
}

private fun Collection<String>.toBoundedNounSnapshot(limits: VersionedSearchLimits): List<String> {
    val snapshot = ArrayList<String>(minOf(size, limits.maxKoreanNouns))
    var totalCharacters = 0L
    for (noun in this) {
        require(snapshot.size < limits.maxKoreanNouns) {
            "Korean noun count exceeds ${limits.maxKoreanNouns}"
        }
        require(noun.length <= limits.maxKoreanNounCharacters) {
            "Korean noun length exceeds ${limits.maxKoreanNounCharacters}"
        }
        totalCharacters += noun.length
        require(totalCharacters <= limits.maxTotalKoreanNounCharacters) {
            "Korean noun total characters exceed ${limits.maxTotalKoreanNounCharacters}"
        }
        snapshot += noun
    }
    return snapshot
}

/** Korean text는 immutable noun automaton으로, 그 밖의 언어는 기존 tokenizer로 처리합니다. */
private class SnapshotKoreanNounTokenizer(
    nouns: Collection<String>,
): SearchTermTokenizer {
    private val koreanNouns: AhoCorasickAutomaton<String> = ahoCorasick {
        ignoreCase = true
        allowOverlaps = true
        normalization = NormalizationForm.NFC
        nouns.forEach { noun -> keyword(noun, noun) }
    }

    override fun tokenize(text: String, language: Language?): List<String> =
        if (language == Language.KOREAN) {
            koreanNouns.parseText(text).map { it.value }.distinct()
        } else {
            tokenizeSearchText(text, language)
        }
}

private fun String.toSearchTermOrNull(): String? {
    val normalized = TextNormalizer.normalize(this).trim { !it.isLetterOrDigit() }
    return normalized.takeIf { it.length >= 2 }
}
