package io.bluetape4k.workshop.text.search

import io.bluetape4k.tokenizer.utils.DictionarySnapshot
import io.bluetape4k.tokenizer.utils.DictionaryVersion
import io.bluetape4k.tokenizer.utils.VersionedDictionary
import io.bluetape4k.workshop.text.detection.LanguageDetectionService
import java.io.Serializable

/** 검색에 사용한 index generation과 ranked hit를 함께 반환합니다. */
data class VersionedSearchResult(
    val version: DictionaryVersion,
    val hits: List<SearchHighlightHit>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 완성된 [MultilingualSearchIndex] generation을 원자적으로 교체하고 제한된 rollback을 제공합니다.
 *
 * loader와 index build는 `VersionedDictionary`의 mutation lock 밖에서 끝납니다. 검색 요청은
 * 시작 시 snapshot을 한 번만 읽으므로 reload 중에도 이전 또는 새 generation 하나만 사용합니다.
 * query tokenizer만 따로 교체하는 부분 갱신은 지원하지 않습니다.
 */
class VersionedMultilingualSearchIndex private constructor(
    private val versions: VersionedDictionary<MultilingualSearchIndex>,
    private val detectionService: LanguageDetectionService,
) {

    /** 현재 검색 index generation의 버전을 반환합니다. */
    fun currentVersion(): DictionaryVersion = versions.snapshot().version

    /** 요청 시작 시 고정한 generation으로 검색하고 사용한 버전을 함께 반환합니다. */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): VersionedSearchResult {
        val current = versions.snapshot()
        return VersionedSearchResult(
            version = current.version,
            hits = current.value.search(query, limit),
        )
    }

    /** caller가 제공한 document collection을 복사해 새 generation으로 공개합니다. */
    fun reload(
        version: DictionaryVersion,
        documents: Collection<SearchDocument>,
    ): DictionaryVersion = reload(version) { documents }

    /**
     * loader와 전체 index build를 mutation lock 밖에서 수행한 뒤 완성된 snapshot만 공개합니다.
     * loader 또는 build 실패와 stale revision은 현재 generation과 history를 변경하지 않습니다.
     */
    fun reload(
        version: DictionaryVersion,
        loader: () -> Collection<SearchDocument>,
    ): DictionaryVersion {
        val documents = loader().toList()
        val candidate = MultilingualSearchIndex.indexOf(documents, detectionService)
        return versions.reload(DictionarySnapshot(version, candidate)).version
    }

    /** bounded history의 가장 최근 index generation으로 되돌아갑니다. */
    fun rollback(): DictionaryVersion = versions.rollback().version

    companion object {
        private const val DEFAULT_LIMIT = 10

        /** 최초 immutable index generation과 bounded history store를 생성합니다. */
        @JvmStatic
        @JvmOverloads
        fun indexOf(
            version: DictionaryVersion,
            documents: Collection<SearchDocument>,
            historyCapacity: Int = 1,
            detectionService: LanguageDetectionService = LanguageDetectionService(),
        ): VersionedMultilingualSearchIndex {
            val initial = MultilingualSearchIndex.indexOf(documents.toList(), detectionService)
            return VersionedMultilingualSearchIndex(
                versions = VersionedDictionary(
                    initial = DictionarySnapshot(version, initial),
                    historyCapacity = historyCapacity,
                ),
                detectionService = detectionService,
            )
        }
    }
}
