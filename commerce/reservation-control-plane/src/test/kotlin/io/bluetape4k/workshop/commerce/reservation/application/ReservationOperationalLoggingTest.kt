package io.bluetape4k.workshop.commerce.reservation.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.workshop.commerce.reservation.redis.InFlightCommandSuppressor
import io.bluetape4k.workshop.commerce.reservation.redis.NodeLocalDatabaseBulkhead
import io.bluetape4k.workshop.commerce.reservation.redis.ReservationAdmissionGate
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

internal class ReservationOperationalLoggingTest {
    @Test
    fun `command boundary logs stable events without raw owner or idempotency credentials`() {
        val rawOwner = "owner-secret-0123456789abcdef0123456789abcdef"
        val rawKey = "idempotency-secret-0123456789abcdef"
        val credentials = ReservationCredentialService("0123456789abcdef0123456789abcdef")
        val gate = ReservationCommandExecutionGate(
            ReservationAdmissionGate(NodeLocalDatabaseBulkhead()),
            InFlightCommandSuppressor(),
            credentials,
            tenantId = "demo",
        )

        val messages = captureReservationLogs {
            credentials.matchesOwner(rawOwner, credentials.ownerDigest(rawOwner))
            check(gate.execute("LOGGING_FIXTURE", rawKey) { "ok" } == "ok")
        }.joinToString("\n")

        messages shouldContain "reservation_owner_verified"
        messages shouldContain "reservation_command_gate_completed"
        messages shouldNotContain rawOwner
        messages shouldNotContain rawKey
        messages shouldNotContain credentials.ownerDigest(rawOwner)
    }

    private fun captureReservationLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(RESERVATION_LOGGER) as Logger
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

    companion object {
        private const val RESERVATION_LOGGER = "io.bluetape4k.workshop.commerce.reservation"
    }
}
