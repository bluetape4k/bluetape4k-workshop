package io.bluetape4k.workshop.text.search

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.tokenizer.korean.utils.KoreanPos
import io.bluetape4k.tokenizer.utils.DictionarySnapshot
import io.bluetape4k.tokenizer.utils.DictionaryVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 완성된 검색 index generation의 reload, rollback, concurrent read 계약을 검증합니다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersionedMultilingualSearchIndexTest {

    @Test
    fun `완성된 index generation을 reload하고 bounded history로 rollback한다`() {
        val index = versionedIndex(historyCapacity = 2)

        index.reload(source(2, listOf(document("v2", "beta document"))))
        index.reload(source(3, listOf(document("v3", "gamma document"))))
        index.reload(source(4, listOf(document("v4", "delta document"))))

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
        index.reload(source(2, listOf(document("v2", "beta document"))))

        assertFailsWith<IllegalStateException> {
            index.reload { error("private loader failure") }
        }
        assertFailsWith<IllegalArgumentException> {
            index.reload(source(2, listOf(document("stale", "stale document"))))
        }
        assertFailsWith<IllegalArgumentException> {
            index.reload(
                source(
                    revision = 3,
                    documents = listOf(document("wrong-name", "wrong name document")),
                    name = "other-search-index",
                ),
            )
        }

        index.currentVersion() shouldBeEqualTo version(2)
        index.rollback() shouldBeEqualTo version(1)
    }

    @Test
    fun `느린 낮은 revision build 중에도 reader와 높은 revision publish는 진행된다`() {
        val index = versionedIndex(historyCapacity = 2)
        val lowBuildStarted = CountDownLatch(1)
        val releaseLowBuild = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val lowReload = executor.submit<Throwable?> {
                runCatching {
                    index.reload {
                        lowBuildStarted.countDown()
                        releaseLowBuild.await(5, TimeUnit.SECONDS).shouldBeTrue()
                        source(2, listOf(document("v2", "beta document")))
                    }
                }.exceptionOrNull()
            }

            lowBuildStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            index.search("alpha").hits.single().document.id shouldBeEqualTo "v1"
            val highReload = executor.submit<DictionaryVersion> {
                index.reload(source(3, listOf(document("v3", "gamma document"))))
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
                index.reload(source(2, listOf(document("v2", "shared beta"))))
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

    @Test
    fun `Korean noun snapshot은 index와 query에 같은 revision으로 고정된다`() {
        val index = VersionedMultilingualSearchIndex.indexOf(
            source = source(
                revision = 1,
                documents = listOf(document("v1", "기존용어안내를 제공합니다.")),
                koreanNouns = listOf("기존용어"),
            ),
            historyCapacity = 1,
        )

        index.search("기존용어").let { result ->
            result.version shouldBeEqualTo version(1)
            result.hits.single().document.id shouldBeEqualTo "v1"
        }

        index.reload(
            source(
                revision = 2,
                documents = listOf(document("v2", "신규용어안내를 제공합니다.")),
                koreanNouns = listOf("신규용어"),
            ),
        )

        index.search("신규용어").let { result ->
            result.version shouldBeEqualTo version(2)
            result.hits.single().document.id shouldBeEqualTo "v2"
        }
        index.search("기존용어").hits.isEmpty().shouldBeTrue()
    }

    @Test
    fun `입력 제한 실패는 현재 generation과 rollback journal을 보존한다`() {
        val index = VersionedMultilingualSearchIndex.indexOf(
            source = source(1, listOf(document("v1", "alpha")), koreanNouns = listOf("기존용어")),
            historyCapacity = 1,
            limits = VersionedSearchLimits(
                maxDocuments = 1,
                maxDocumentCharacters = 5,
                maxTotalDocumentCharacters = 5,
                maxKoreanNouns = 1,
                maxKoreanNounCharacters = 4,
                maxTotalKoreanNounCharacters = 4,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            index.reload(source(2, listOf(document("v2", "toolong")), koreanNouns = listOf("신규용어")))
        }
        assertFailsWith<IllegalArgumentException> {
            index.reload(source(2, listOf(document("v2", "beta")), koreanNouns = listOf("신규용어", "추가용어")))
        }
        assertFailsWith<IllegalArgumentException> {
            index.reload(source(2, listOf(document("v2", "beta")), koreanNouns = listOf("아주긴용어")))
        }

        index.currentVersion() shouldBeEqualTo version(1)
        assertFailsWith<IllegalStateException> { index.rollback() }
    }

    @Test
    fun `reload log는 revision과 count만 남기고 noun을 노출하지 않는다`() {
        val index = versionedIndex(historyCapacity = 1)
        val logs = captureLogs {
            index.reload(
                source(
                    revision = 2,
                    documents = listOf(document("v2", "비공개용어 안내")),
                    koreanNouns = listOf("비공개용어"),
                ),
            )
        }.joinToString("\n")

        logs shouldContain "operation=reload"
        logs shouldContain "previousRevision=1"
        logs shouldContain "revision=2"
        logs shouldContain "koreanNounCount=1"
        logs shouldNotContain "비공개용어"
    }

    @Test
    fun `caller의 mutable source는 generation build 시점에 복사된다`() {
        val documents = mutableListOf(document("v1", "기존용어 안내"))
        val nouns = mutableSetOf("기존용어")
        val index = VersionedMultilingualSearchIndex.indexOf(source(1, documents, nouns))

        documents.clear()
        nouns.clear()

        index.search("기존용어").hits.single().document.id shouldBeEqualTo "v1"
    }

    private fun versionedIndex(historyCapacity: Int): VersionedMultilingualSearchIndex =
        VersionedMultilingualSearchIndex.indexOf(
            source = source(1, listOf(document("v1", "shared alpha"))),
            historyCapacity = historyCapacity,
        )

    private fun version(revision: Long): DictionaryVersion =
        DictionaryVersion("korean-dictionary", revision)

    private fun source(
        revision: Long,
        documents: Collection<SearchDocument>,
        koreanNouns: Collection<String> = listOf("기존용어", "신규용어"),
        name: String = "korean-dictionary",
    ): VersionedMultilingualSearchSource =
        VersionedMultilingualSearchSource(
            koreanDictionary = DictionarySnapshot(
                version = DictionaryVersion(name, revision),
                value = mapOf(KoreanPos.Noun to koreanNouns.toSet()),
            ),
            documents = documents,
        )

    private fun document(id: String, text: String): SearchDocument =
        SearchDocument.of(id = id, title = "$id title", text = text)

    private fun captureLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(VersionedMultilingualSearchIndex::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val previousLevel = logger.level
        logger.level = Level.INFO
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }
}
