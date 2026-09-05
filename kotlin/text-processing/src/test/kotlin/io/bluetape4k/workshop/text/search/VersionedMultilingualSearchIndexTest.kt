package io.bluetape4k.workshop.text.search

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.tokenizer.utils.DictionaryVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 완성된 검색 index generation의 reload, rollback, concurrent read 계약을 검증합니다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersionedMultilingualSearchIndexTest {

    @Test
    fun `완성된 index generation을 reload하고 bounded history로 rollback한다`() {
        val index = versionedIndex(historyCapacity = 2)

        index.reload(version(2), listOf(document("v2", "beta document")))
        index.reload(version(3), listOf(document("v3", "gamma document")))
        index.reload(version(4), listOf(document("v4", "delta document")))

        index.search("delta").let { result ->
            result.version shouldBeEqualTo version(4)
            result.hits.single().document.id shouldBeEqualTo "v4"
        }
        index.rollback() shouldBeEqualTo version(3)
        index.rollback() shouldBeEqualTo version(2)
        assertFailsWith<IllegalStateException> { index.rollback() }
    }

    @Test
    fun `loader와 stale revision 실패는 current와 rollback journal을 보존한다`() {
        val index = versionedIndex(historyCapacity = 1)
        index.reload(version(2), listOf(document("v2", "beta document")))

        assertFailsWith<IllegalStateException> {
            index.reload(version(3)) { error("private loader failure") }
        }
        assertFailsWith<IllegalArgumentException> {
            index.reload(version(2), listOf(document("stale", "stale document")))
        }
        assertFailsWith<IllegalArgumentException> {
            index.reload(
                DictionaryVersion("other-search-index", 3),
                listOf(document("wrong-name", "wrong name document")),
            )
        }

        index.currentVersion() shouldBeEqualTo version(2)
        index.rollback() shouldBeEqualTo version(1)
    }

    @Test
    fun `느린 낮은 revision build는 높은 revision publish를 막지 않는다`() {
        val index = versionedIndex(historyCapacity = 2)
        val lowBuildStarted = CountDownLatch(1)
        val releaseLowBuild = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val lowReload = executor.submit<Throwable?> {
                runCatching {
                    index.reload(version(2)) {
                        lowBuildStarted.countDown()
                        releaseLowBuild.await(5, TimeUnit.SECONDS).shouldBeTrue()
                        listOf(document("v2", "beta document"))
                    }
                }.exceptionOrNull()
            }

            lowBuildStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val highReload = executor.submit<DictionaryVersion> {
                index.reload(version(3), listOf(document("v3", "gamma document")))
            }

            highReload.get(2, TimeUnit.SECONDS) shouldBeEqualTo version(3)
            index.search("gamma").hits.single().document.id shouldBeEqualTo "v3"

            releaseLowBuild.countDown()
            (lowReload.get(2, TimeUnit.SECONDS) is IllegalArgumentException).shouldBeTrue()
            index.currentVersion() shouldBeEqualTo version(3)
        } finally {
            releaseLowBuild.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `동시 reader는 version과 일치하는 완전한 index만 관측한다`() {
        val index = versionedIndex(historyCapacity = 1)
        val executor = Executors.newFixedThreadPool(5)
        val start = CountDownLatch(1)

        try {
            val readers = List(4) {
                executor.submit<List<Pair<Long, String?>>> {
                    start.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    List(100) {
                        val result = index.search("shared")
                        result.version.revision to result.hits.singleOrNull()?.document?.id
                    }
                }
            }
            val writer = executor.submit {
                start.await(5, TimeUnit.SECONDS).shouldBeTrue()
                index.reload(version(2), listOf(document("v2", "shared beta")))
            }

            start.countDown()
            writer.get(5, TimeUnit.SECONDS)
            readers.flatMap { it.get(10, TimeUnit.SECONDS) }.forEach { (revision, id) ->
                when (revision) {
                    1L -> id shouldBeEqualTo "v1"
                    2L -> id shouldBeEqualTo "v2"
                    else -> error("unexpected revision=$revision")
                }
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun versionedIndex(historyCapacity: Int): VersionedMultilingualSearchIndex =
        VersionedMultilingualSearchIndex.indexOf(
            version = version(1),
            documents = listOf(document("v1", "shared alpha")),
            historyCapacity = historyCapacity,
        )

    private fun version(revision: Long): DictionaryVersion =
        DictionaryVersion("multilingual-search-index", revision)

    private fun document(id: String, text: String): SearchDocument =
        SearchDocument.of(id = id, title = "$id title", text = text)
}
