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
 * T7 - [LeaderElectionOptions.autoExtend] = `true` 설정이 WARN 로그를 남긴 뒤 조용히 무시되고,
 * 작업은 계속 성공적으로 실행됨을 단언해 R16(ZooKeeper에는 TTL이 없음)을 검증한다.
 *
 * ## 동작 / 계약
 * - [ZooKeeperLeaderElector]가 내보내는 WARN 이벤트를 잡기 위해
 *   `io.bluetape4k.leader.zookeeper` 라이브러리 logger에 Logback [ListAppender]를 붙인다.
 * - WARN 메시지는 한국어이지만 ASCII 토큰 `"autoExtend"`를 그대로 포함한다.
 *   따라서 단언은 `formattedMessage`에서 해당 부분 문자열을 확인한다. 이 부분 문자열을 바꾸면 안 된다.
 * - `@AfterEach`에서는 `stop()` 전에 appender를 logger에서 반드시 분리해야 한다.
 *   그렇지 않으면 중지된 appender가 같은 logger를 공유하는 후속 테스트로 누수된다.
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
        // stop()만으로는 appender가 logger에서 제거되지 않으므로 stop() 전에 detachAppender를 반드시 호출한다.
        // 그렇지 않으면 중지된 appender가 후속 테스트로 누수된다.
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
        // WARN 메시지는 한국어지만 ASCII 토큰 "autoExtend"를 그대로 포함한다.
        // ZooKeeperLeaderElector.kt:111-113에서 확인한 계약이므로 이 부분 문자열을 바꾸면 안 된다.
        val warnEvents = appender.list.filter {
            it.level == Level.WARN && "autoExtend" in it.formattedMessage
        }
        warnEvents.shouldNotBeEmpty()
    }
}
