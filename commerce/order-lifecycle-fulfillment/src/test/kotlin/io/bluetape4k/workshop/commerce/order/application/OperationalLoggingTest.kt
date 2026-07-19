package io.bluetape4k.workshop.commerce.order.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import io.bluetape4k.workshop.commerce.order.idempotency.AcquireResult
import io.bluetape4k.workshop.commerce.order.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.order.idempotency.IdempotencyFingerprint
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class OperationalLoggingTest {
    @Test
    fun `idempotency decision logs hash prefix without customer input or payload`() {
        val rawKey = "secret-idempotency-key-532"
        val customerReference = "customer-secret-reference"
        val sku = "sku-secret-payload"
        val idempotency = mockk<HttpIdempotencyRepository>()
        every { idempotency.acquire(any(), any(), any(), any(), any(), any()) } returns
            AcquireResult.FingerprintConflict
        val service =
            IdempotentOrderSubmissionService(
                idempotency,
                mockk<OrderCommandService>(),
                Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC)
            )

        val messages =
            captureApplicationLogs {
                service.submit(
                    rawKey,
                    SubmitOrderRequest(
                        tenantId = "tenant-a",
                        customerReference = customerReference,
                        providerMode = ProviderMode.SUCCESS,
                        lines = listOf(SubmitOrderLineRequest(sku, 1, BigDecimal("10.00")))
                    )
                )
            }.joinToString("\n")

        messages shouldContain "idempotency_fingerprint_conflict"
        messages shouldContain "keyHashPrefix=${IdempotencyFingerprint.key(rawKey).take(12)}"
        messages shouldNotContain rawKey
        messages shouldNotContain customerReference
        messages shouldNotContain sku
    }

    private fun captureApplicationLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(APPLICATION_LOGGER) as Logger
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
        private const val APPLICATION_LOGGER = "io.bluetape4k.workshop.commerce.order.application"
    }
}
