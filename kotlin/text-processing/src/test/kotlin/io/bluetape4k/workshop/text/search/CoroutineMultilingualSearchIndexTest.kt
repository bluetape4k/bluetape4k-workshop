package io.bluetape4k.workshop.text.search

import com.github.pemistahl.lingua.api.Language
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendDefault
import io.bluetape4k.workshop.text.detection.CoroutineLanguageDetectionService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.ConcurrentLinkedQueue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoroutineMultilingualSearchIndexTest {

    private val documents = listOf(
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
    )

    @Test
    fun `builds and searches multilingual documents from suspend API`() = runSuspendDefault {
        val index = CoroutineMultilingualSearchIndex.indexOf(documents)

        val hits = index.search("서울 카페")

        hits shouldHaveSize 1
        val hit = hits.single()
        hit.document.id shouldBeEqualTo "ko-1"
        hit.language shouldBeEqualTo Language.KOREAN
        hit.matches.map { it.text } shouldContain "서울"
        hit.matches.map { it.text } shouldContain "카페"
    }

    @Test
    fun `search is stable under concurrent coroutine callers`() = runSuspendDefault {
        val detectionService = CoroutineLanguageDetectionService()
        val index = CoroutineMultilingualSearchIndex.indexOf(
            documents = documents,
            detectionService = detectionService,
        )
        val hitIds = ConcurrentLinkedQueue<String>()
        val queries = listOf(
            "shipment retry" to "en-1",
            "서울 카페" to "ko-1",
            "東京 観光" to "ja-1",
        )

        SuspendedJobTester()
            .workers(8)
            .rounds(24)
            .add {
                val (query, expectedId) = queries.random()
                val hits = index.search(query)

                hits shouldHaveSize 1
                hits.single().document.id shouldBeEqualTo expectedId
                hitIds += hits.single().document.id
            }
            .run()

        hitIds shouldHaveSize 24
    }

    @Test
    fun `rejects duplicate document ids from suspend factory`() = runSuspendDefault {
        var failure: IllegalArgumentException? = null

        try {
            CoroutineMultilingualSearchIndex.indexOf(
                listOf(
                    SearchDocument.of("same-id", "First", "first document"),
                    SearchDocument.of("same-id", "Second", "second document"),
                )
            )
        } catch (e: IllegalArgumentException) {
            failure = e
        }

        failure.shouldNotBeNull()
        failure.message shouldBeEqualTo "documents must have unique ids."
    }
}
