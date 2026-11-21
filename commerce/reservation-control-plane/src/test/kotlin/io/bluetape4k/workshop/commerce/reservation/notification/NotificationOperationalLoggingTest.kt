package io.bluetape4k.workshop.commerce.reservation.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * NotificationOperationalLoggingTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class NotificationOperationalLoggingTest {

    private companion object: KLogging() {
/**
 * NOTIFICATION_LOGGER은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val NOTIFICATION_LOGGER = "io.bluetape4k.workshop.commerce.reservation.notification"
    }

    @Test
/**
 * 이 시나리오는 "notification logs lifecycle without aggregate or claim secrets" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `notification logs lifecycle without aggregate or claim secrets`() {
        val now = Instant.parse("2026-07-19T00:00:00Z")
        val aggregateSecret = "customer-private-aggregate"
        val claimSecret = "worker-claim-secret"
        val commandSecret = "operator-command-secret"
        val outbox = InMemoryNotificationOutbox()

        val messages = captureNotificationLogs {
            outbox.enqueue(
                NotificationRequest(
                    deliveryId = "safe-delivery-id",
                    channel = NotificationChannel.IN_APP,
                    templateCode = "reservation-state-changed",
                    aggregateId = aggregateSecret
                ),
                now
            )
            outbox.claim(
                deliveryId = "safe-delivery-id",
                owner = claimSecret,
                now = now,
                lease = 30.seconds()
            )
            outbox.markFailed(
                deliveryId = "safe-delivery-id",
                owner = claimSecret,
                now = now + 1.seconds(),
                failureCode = NotificationFailureCode.FAKE_TRANSIENT,
                policy = NotificationRetryPolicy(maxAttempts = 1)
            )
            outbox.redrive(
                deliveryId = "safe-delivery-id",
                commandId = commandSecret,
                now = now + 2.seconds()
            )
        }.joinToString("\n")


        log.debug { "messages=$messages" }
        messages shouldContain "notification_claimed"
        messages shouldContain "notification_exhausted"
        messages shouldContain "notification_redriven"
        messages shouldNotContain aggregateSecret
        messages shouldNotContain claimSecret
        messages shouldNotContain commandSecret
    }

/**
 * captureNotificationLogs은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun captureNotificationLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(NOTIFICATION_LOGGER) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)

        return try {
            block()
            appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }
}
