package io.bluetape4k.workshop.leader.zookeeper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * T7 — Verifies R16 (ZooKeeper has no TTL) by asserting that setting
 * [LeaderElectionOptions.autoExtend] = `true` emits a WARN log and is then silently ignored
 * while the action still executes successfully.
 *
 * ## Behavior / Contract
 * - Attaches a Logback [ListAppender] to the library logger `io.bluetape4k.leader.zookeeper`
 *   to capture WARN events emitted by [ZooKeeperLeaderElector].
 * - The WARN message is localized in Korean but contains the ASCII token `"autoExtend"` verbatim,
 *   so the assertion checks for that substring in `formattedMessage`. Do NOT change this substring.
 * - In `@AfterEach`, the appender MUST be detached from the logger BEFORE `stop()`; otherwise
 *   the stopped appender leaks into subsequent tests sharing the same logger.
 */
class R16AutoExtendIgnoredTest: AbstractLeaderZookeeperTest() {

    companion object: KLogging() {
        private const val LIBRARY_LOGGER_NAME = "io.bluetape4k.leader.zookeeper"
    }

    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setupLogCapture() {
        val logger = LoggerFactory.getLogger(LIBRARY_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun teardownLogCapture() {
        // MUST detachAppender BEFORE stop() — stop() alone does NOT remove the appender from
        // the logger, leaking the stopped appender into subsequent tests.
        val logger = LoggerFactory.getLogger(LIBRARY_LOGGER_NAME) as Logger
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `autoExtend option is silently ignored with WARN log`() {
        val elector = ZooKeeperLeaderElector(
            curator,
            "/test/r16",
            LeaderElectionOptions(autoExtend = true, waitTime = 500.milliseconds)
        )

        val result = elector.runIfLeader(randomLockName("t7")) { "r16-done" }

        result shouldBeEqualTo "r16-done"
        // WARN message is in Korean but contains ASCII token "autoExtend" verbatim — source-confirmed
        // from ZooKeeperLeaderElector.kt:111-113. Do NOT change this substring.
        val warnEvents = appender.list.filter {
            it.level == Level.WARN && "autoExtend" in it.formattedMessage
        }
        warnEvents.shouldNotBeEmpty()
    }
}
