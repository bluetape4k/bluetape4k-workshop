package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcExecutionLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcTimeoutPhase
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.UUID

internal class VoucherPoolExceptionHandlerTest {
    @Test
    fun `JDBC timeout is a safe retryable service unavailable response`() {
        val request = MockHttpServletRequest().apply {
            setAttribute(REQUEST_ID_ATTRIBUTE, "request-timeout")
        }
        val failure =
            VoucherPoolJdbcTimeoutException(
                JdbcExecutionLane.OPERATOR,
                JdbcTimeoutPhase.TRANSACTION,
                TimeoutException("sensitive backend details"),
            )

        val response = VoucherPoolExceptionHandler().jdbcTimeout(failure, request)

        response.statusCode.value() shouldBeEqualTo 503
        response.body shouldBeEqualTo
            ApiError(
                code = "BACKEND_TIMEOUT",
                reason = "voucher pool is temporarily unavailable",
                requestId = "request-timeout",
                retryAfterSeconds = 1,
            )
    }

    @Test
    fun `expired replay preserves only the safe public effect id`() {
        val effectId = UUID.randomUUID()

        apiFailure(
            io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED,
            effectId,
        ).toApiError("request-expired") shouldBeEqualTo
            ApiError(
                code = "REPLAY_WINDOW_EXPIRED",
                reason = "the replay window has expired",
                requestId = "request-expired",
                effectId = effectId,
            )
    }

    @Test
    fun `expired diagnostics release both records and insertion order keys`() {
        var now = Instant.parse("2026-07-21T00:00:00Z")
        val registry = VoucherPoolDiagnosticRegistry({ now }, Duration.ofMinutes(15))
        repeat(200) { index ->
            registry.record("request-$index", "tenant-a", "GET", "/api/v1/snapshots", 200, 1)
        }

        now = now.plus(Duration.ofMinutes(16))
        repeat(200) { index -> registry.find("tenant-a", "request-$index") shouldBeEqualTo null }

        registry.queuedKeyCount() shouldBeEqualTo 0
    }
}
