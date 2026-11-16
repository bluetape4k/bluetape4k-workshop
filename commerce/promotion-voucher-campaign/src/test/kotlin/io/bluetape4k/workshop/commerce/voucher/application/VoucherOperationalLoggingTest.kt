package io.bluetape4k.workshop.commerce.voucher.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import io.bluetape4k.workshop.commerce.voucher.web.AllocationHttpResponse
import io.bluetape4k.workshop.commerce.voucher.web.ApiExceptionHandler
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.test.web.reactive.server.expectBody

internal class VoucherOperationalLoggingTest: AbstractVoucherIntegrationTest() {

    private companion object: KLogging() {
        const val VOUCHER_LOGGER = "io.bluetape4k.workshop.commerce.voucher"
    }

    @Test
    fun `live command logs stable events without customer or credential material`() {
        val tenant = randomIdentifier()
        val principal = randomIdentifier()
        val idempotencyKey = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)

        val (allocation, messages) = captureVoucherLogs {
            customerPost(
                tenant,
                principal,
                "/api/v1/campaigns/$campaignId/claims",
                idempotencyKey,
                mapOf("userRef" to principal),
            ).exchange()
                .expectStatus().isCreated
                .expectBody<AllocationHttpResponse>()
                .returnResult().responseBody.shouldNotBeNull()
        }

        val rendered = messages.joinToString("\n")

        log.debug { "rendered=$rendered" }
        rendered shouldContain "voucher_command_completed"
        rendered shouldContain "voucher_http_completed"
        rendered shouldNotContain tenant
        rendered shouldNotContain principal
        rendered shouldNotContain idempotencyKey
        rendered shouldNotContain OPERATOR_SECRET
        rendered shouldNotContain allocation.code.shouldNotBeNull()
    }

    @Test
    fun `unexpected failures log only the exception type without attaching sensitive throwable text`() {
        val request = mockk<HttpServletRequest>()
        every { request.getAttribute(any()) } returns "request-safe"
        val sensitive = "tenant-secret sql-bind-secret"

        val (_, events) = captureVoucherEvents {
            ApiExceptionHandler().unexpected(IllegalStateException(sensitive), request)
        }

        val rendered = events.joinToString("\n") { it.formattedMessage }

        log.debug { "rendered=$rendered" }
        rendered shouldContain "failure=IllegalStateException"
        rendered shouldNotContain sensitive

        events.all { it.throwableProxy == null }.shouldBeTrue()
    }

    private fun <T> captureVoucherLogs(block: () -> T): Pair<T, List<String>> {
        val (result, events) = captureVoucherEvents(block)
        return result to events.map { it.formattedMessage }
    }

    private inline fun <T> captureVoucherEvents(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(VOUCHER_LOGGER) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)

        return try {
            block() to appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }
}
