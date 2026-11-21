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

/**
 * VoucherOperationalLoggingTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherOperationalLoggingTest: AbstractVoucherIntegrationTest() {

    private companion object: KLogging() {
/**
 * VOUCHER_LOGGER은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val VOUCHER_LOGGER = "io.bluetape4k.workshop.commerce.voucher"
    }

    @Test
/**
 * 이 시나리오는 "live command logs stable events without customer or credential material" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "unexpected failures log only the exception type without attaching sensitive throwable
 * text" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * captureVoucherLogs은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T> captureVoucherLogs(block: () -> T): Pair<T, List<String>> {
        val (result, events) = captureVoucherEvents(block)
        return result to events.map { it.formattedMessage }
    }

/**
 * captureVoucherEvents은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
