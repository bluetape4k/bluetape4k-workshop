package io.bluetape4k.workshop.commerce.voucher.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import io.bluetape4k.workshop.commerce.voucher.web.AllocationHttpResponse
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

internal class VoucherOperationalLoggingTest : AbstractVoucherIntegrationTest() {
    @Test
    fun `live command logs stable events without customer or credential material`() {
        val tenant = randomIdentifier()
        val principal = randomIdentifier()
        val idempotencyKey = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)

        val (allocation, messages) =
            captureVoucherLogs {
                customerPost(
                    tenant,
                    principal,
                    "/api/v1/campaigns/$campaignId/claims",
                    idempotencyKey,
                    mapOf("userRef" to principal),
                ).exchange().expectStatus().isCreated
                    .expectBody(AllocationHttpResponse::class.java)
                    .returnResult().responseBody!!
            }
        val rendered = messages.joinToString("\n")

        rendered shouldContain "voucher_command_completed"
        rendered shouldContain "voucher_http_completed"
        rendered shouldNotContain tenant
        rendered shouldNotContain principal
        rendered shouldNotContain idempotencyKey
        rendered shouldNotContain OPERATOR_SECRET
        rendered shouldNotContain allocation.code!!
    }

    private fun <T> captureVoucherLogs(block: () -> T): Pair<T, List<String>> {
        val logger = LoggerFactory.getLogger(VOUCHER_LOGGER) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        return try {
            block() to appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }

    private companion object {
        const val VOUCHER_LOGGER = "io.bluetape4k.workshop.commerce.voucher"
    }
}
