package io.bluetape4k.workshop.textmoderation.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.tokenizer.utils.DictionaryVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory

/** moderation blockword의 copy-on-write, bounded history, safe metadata 계약을 검증합니다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersionedModerationDictionaryTest {

    @Test
    fun `blockword를 reload하고 bounded history 순서로 rollback한다`() {
        val dictionary = dictionary(historyCapacity = 2)

        dictionary.reload(version(2), listOf("beta"))
        dictionary.reload(version(3), listOf("gamma"))
        dictionary.reload(version(4), listOf("delta"))

        dictionary.currentMetadata().version shouldBeEqualTo version(4)
        dictionary.rollback().version shouldBeEqualTo version(3)
        dictionary.rollback().version shouldBeEqualTo version(2)
        assertFailsWith<IllegalStateException> { dictionary.rollback() }
    }

    @Test
    fun `loader와 stale revision 실패는 current와 journal을 보존한다`() {
        val dictionary = dictionary(historyCapacity = 1)
        dictionary.reload(version(2), listOf("beta"))

        assertFailsWith<IllegalStateException> {
            dictionary.reload(version(3)) { error("secret-source-token") }
        }
        assertFailsWith<IllegalArgumentException> {
            dictionary.reload(version(2), listOf("stale"))
        }
        assertFailsWith<IllegalArgumentException> {
            dictionary.reload(DictionaryVersion("other-blockwords", 3), listOf("wrong-name"))
        }

        dictionary.currentMetadata().version shouldBeEqualTo version(2)
        dictionary.rollback().version shouldBeEqualTo version(1)
    }

    @Test
    fun `입력 제한 실패는 현재 snapshot을 변경하지 않는다`() {
        val dictionary = VersionedModerationDictionary(
            initialVersion = version(1),
            initialWords = listOf("alpha"),
            historyCapacity = 1,
            limits = ModerationDictionaryLimits(
                maxWords = 2,
                maxWordCharacters = 5,
                maxTotalCharacters = 8,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            dictionary.reload(version(2), listOf("toolong"))
        }
        assertFailsWith<IllegalArgumentException> {
            dictionary.reload(version(2), listOf("alpha", "beta"))
        }

        dictionary.currentMetadata() shouldBeEqualTo ModerationDictionaryMetadata(
            version = version(1),
            wordCount = 1,
            totalCharacters = 5,
        )
        assertFailsWith<IllegalStateException> { dictionary.rollback() }
    }

    @Test
    fun `reload log는 revision과 count만 남기고 blockword를 노출하지 않는다`() {
        val dictionary = dictionary(historyCapacity = 1)
        val logs = captureLogs {
            dictionary.reload(version(2), listOf("private-blockword"))
        }.joinToString("\n")

        logs shouldContain "operation=reload"
        logs shouldContain "revision=2"
        logs shouldContain "wordCount=1"
        logs shouldNotContain "private-blockword"
    }

    private fun dictionary(historyCapacity: Int): VersionedModerationDictionary =
        VersionedModerationDictionary(
            initialVersion = version(1),
            initialWords = listOf("alpha"),
            historyCapacity = historyCapacity,
        )

    private fun version(revision: Long): DictionaryVersion =
        DictionaryVersion("moderation-blockwords", revision)

    private fun captureLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(VersionedModerationDictionary::class.java) as Logger
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
