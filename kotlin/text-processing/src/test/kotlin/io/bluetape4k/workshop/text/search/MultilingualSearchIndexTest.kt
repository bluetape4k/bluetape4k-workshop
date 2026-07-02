package io.bluetape4k.workshop.text.search

import com.github.pemistahl.lingua.api.Language
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.text.detection.LanguageDetectionService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultilingualSearchIndexTest {

    companion object : KLogging()

    private val detectionService = LanguageDetectionService()

    private val index = MultilingualSearchIndex.indexOf(
        documents = listOf(
            SearchDocument.of(
                id = "en-1",
                title = "Retry shipment",
                text = "A shipment retry event updates the tracking pipeline.",
            ),
            SearchDocument.of(
                id = "ko-1",
                title = "Seoul cafe",
                text = "서울 카페 예약 문서는 한국어 검색 예제를 설명합니다.",
            ),
            SearchDocument.of(
                id = "ja-1",
                title = "Tokyo travel",
                text = "東京の観光案内は日本語検索の例です。",
            ),
            SearchDocument.of(
                id = "en-2",
                title = "Overlap sample",
                text = "banana bandana",
            ),
        ),
        detectionService = detectionService,
    )

    @Test
    fun `indexes and highlights English documents`() {
        val hits = index.search("shipment retry")

        hits shouldHaveSize 1
        val hit = hits.single()
        hit.document.id shouldBeEqualTo "en-1"
        hit.language shouldBeEqualTo Language.ENGLISH
        hit.score shouldBeEqualTo 2
        hit.highlightedText shouldBeEqualTo "A <mark>shipment</mark> <mark>retry</mark> event updates the tracking pipeline."
    }

    @Test
    fun `indexes and highlights Korean documents`() {
        val hits = index.search("서울 카페")

        hits shouldHaveSize 1
        val hit = hits.single()
        hit.document.id shouldBeEqualTo "ko-1"
        hit.language shouldBeEqualTo Language.KOREAN
        hit.matches.map { it.text } shouldContain "서울"
        hit.matches.map { it.text } shouldContain "카페"
        hit.highlightedText shouldBeEqualTo "<mark>서울</mark> <mark>카페</mark> 예약 문서는 한국어 검색 예제를 설명합니다."
    }

    @Test
    fun `indexes and highlights Japanese documents`() {
        val hits = index.search("東京 観光")

        hits shouldHaveSize 1
        val hit = hits.single()
        hit.document.id shouldBeEqualTo "ja-1"
        hit.language shouldBeEqualTo Language.JAPANESE
        hit.matches.map { it.text } shouldContain "東京"
        hit.matches.map { it.text } shouldContain "観光"
        hit.highlightedText shouldBeEqualTo "<mark>東京</mark>の<mark>観光</mark>案内は日本語検索の例です。"
    }

    @Test
    fun `returns empty list for no match`() {
        val hits = index.search("payment invoice")

        hits shouldHaveSize 0
    }

    @Test
    fun `normalizes query case before candidate lookup and highlighting`() {
        val hits = index.search("SHIPMENT")

        hits shouldHaveSize 1
        hits.single().highlightedText shouldBeEqualTo "A <mark>shipment</mark> retry event updates the tracking pipeline."
    }

    @Test
    fun `keeps overlapping match spans while rendering deterministic highlights`() {
        val hits = index.search("ana banana")

        hits shouldHaveSize 1
        val hit = hits.single()
        hit.document.id shouldBeEqualTo "en-2"
        hit.matches.shouldNotBeEmpty()
        hit.matches.map { it.text } shouldContain "banana"
        hit.matches.map { it.text } shouldContain "ana"
        hit.highlightedText shouldBeEqualTo "<mark>banana</mark> band<mark>ana</mark>"
    }

    @Test
    fun `records detected language and token set for learners`() {
        val languages = index.indexedDocuments.associate { it.document.id to it.language }

        languages["en-1"] shouldBeEqualTo Language.ENGLISH
        languages["ko-1"] shouldBeEqualTo Language.KOREAN
        languages["ja-1"] shouldBeEqualTo Language.JAPANESE
    }

    @Test
    fun `rejects duplicate document ids`() {
        assertFailsWith<IllegalArgumentException> {
            MultilingualSearchIndex.indexOf(
                listOf(
                    SearchDocument.of(
                        id = "same-id",
                        title = "First",
                        text = "first document",
                    ),
                    SearchDocument.of(
                        id = "same-id",
                        title = "Second",
                        text = "second document",
                    ),
                ),
                detectionService = detectionService,
            )
        }
    }
}
