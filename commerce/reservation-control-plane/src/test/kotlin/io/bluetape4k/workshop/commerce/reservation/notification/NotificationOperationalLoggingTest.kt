package io.bluetape4k.workshop.commerce.reservation.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class NotificationOperationalLoggingTest {
    @Test
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
                    aggregateId = aggregateSecret,
                ),
                now,
            )
            outbox.claim("safe-delivery-id", claimSecret, now, Duration.ofSeconds(30))
            outbox.markFailed(
                "safe-delivery-id",
                claimSecret,
                now.plusSeconds(1),
                NotificationFailureCode.FAKE_TRANSIENT,
                NotificationRetryPolicy(maxAttempts = 1),
            )
            outbox.redrive("safe-delivery-id", commandSecret, now.plusSeconds(2))
        }.joinToString("\n")

        messages shouldContain "notification_claimed"
        messages shouldContain "notification_exhausted"
        messages shouldContain "notification_redriven"
        messages shouldNotContain aggregateSecret
        messages shouldNotContain claimSecret
        messages shouldNotContain commandSecret
    }

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

    private companion object {
        const val NOTIFICATION_LOGGER = "io.bluetape4k.workshop.commerce.reservation.notification"
    }
}
