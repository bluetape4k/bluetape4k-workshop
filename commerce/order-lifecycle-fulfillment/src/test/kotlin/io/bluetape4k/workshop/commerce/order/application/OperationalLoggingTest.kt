package io.bluetape4k.workshop.commerce.order.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import io.bluetape4k.workshop.commerce.order.idempotency.AcquireResult
import io.bluetape4k.workshop.commerce.order.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.order.idempotency.IdempotencyFingerprint
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * OperationalLoggingTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OperationalLoggingTest {

    companion object: KLogging() {
/**
 * APPLICATION_LOGGER은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val APPLICATION_LOGGER = "io.bluetape4k.workshop.commerce.order.application"
    }

/**
 * idempotency은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val idempotency = mockk<HttpIdempotencyRepository>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(idempotency)
    }

    @Test
/**
 * 이 시나리오는 "idempotency decision logs hash prefix without customer input or payload" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `idempotency decision logs hash prefix without customer input or payload`() {
        val rawKey = "secret-idempotency-key-532"
        val customerReference = "customer-secret-reference"
        val sku = "sku-secret-payload"

        every {
            idempotency.acquire(any(), any(), any(), any(), any(), any())
        } returns AcquireResult.FingerprintConflict

        val service = IdempotentOrderSubmissionService(
            idempotency,
            mockk<OrderCommandService>(),
            Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC)
        )

        val messages = captureApplicationLogs {
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

        log.debug { "messages=$messages" }
        messages shouldContain "idempotency_fingerprint_conflict"
        messages shouldContain "keyHashPrefix=${IdempotencyFingerprint.key(rawKey).take(12)}"
        messages shouldNotContain rawKey
        messages shouldNotContain customerReference
        messages shouldNotContain sku
    }

/**
 * captureApplicationLogs은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun captureApplicationLogs(block: () -> Unit): List<String> {
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
}
