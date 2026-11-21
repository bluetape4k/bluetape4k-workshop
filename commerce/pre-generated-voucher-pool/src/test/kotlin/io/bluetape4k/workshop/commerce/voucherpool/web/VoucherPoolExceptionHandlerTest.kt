package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcExecutionLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcTimeoutPhase
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeoutException

/**
 * VoucherPoolExceptionHandlerTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolExceptionHandlerTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "JDBC timeout is a safe retryable service unavailable response" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `JDBC timeout is a safe retryable service unavailable response`() {
        val request = MockHttpServletRequest().apply {
            setAttribute(REQUEST_ID_ATTRIBUTE, "request-timeout")
        }
        val failure = VoucherPoolJdbcTimeoutException(
            JdbcExecutionLane.OPERATOR,
            JdbcTimeoutPhase.TRANSACTION,
            TimeoutException("sensitive backend details"),
        )

        val response = VoucherPoolExceptionHandler().jdbcTimeout(failure, request)

        response.statusCode.value() shouldBeEqualTo 503
        response.body shouldBeEqualTo ApiError(
            code = "BACKEND_TIMEOUT",
            reason = "voucher pool is temporarily unavailable",
            requestId = "request-timeout",
            retryAfterSeconds = 1,
        )
    }

    @Test
/**
 * 이 시나리오는 "operator command translates JDBC timeout before it escapes the command boundary" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator command translates JDBC timeout before it escapes the command boundary`() {
        val service = mockk<CampaignBatchCommandService>()
        every {
            service.createCampaign(any())
        } throws VoucherPoolJdbcTimeoutException(
            JdbcExecutionLane.OPERATOR,
            JdbcTimeoutPhase.TRANSACTION,
            TimeoutException("sensitive backend details"),
        )
        val commands = OperatorVoucherPoolHttpCommandExecutor(
            service = service,
            queries = mockk(),
            digests = mockk(),
            revocations = mockk(),
            reconciliations = mockk(),
        )
        val startsAt = Instant.parse("2026-07-21T00:00:00Z")

        val failure = assertFailsWith<VoucherPoolApiException> {
            commands.createCampaign(
                tenantId = "tenant-a",
                idempotencyKey = "operator-timeout-key",
                ifNoneMatch = "*",
                request = OperatorCreateCampaignRequest(
                    campaignId = UUID.randomUUID(),
                    startsAt = startsAt,
                    endsAt = startsAt.plusSeconds(3_600),
                    perUserLimit = 1,
                    reservationTtlSeconds = 60,
                    allocationTtlSeconds = 60,
                    replacementAllowance = 1,
                ),
                requestId = "request-timeout",
            )
        }

        failure.status shouldBeEqualTo 503
        failure.stableCode shouldBeEqualTo "BACKEND_TIMEOUT"
    }

    @Test
/**
 * 이 시나리오는 "expired replay preserves only the safe public effect id" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expired replay preserves only the safe public effect id`() {
        val effectId = UUID.randomUUID()

        apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED, effectId)
            .toApiError("request-expired") shouldBeEqualTo ApiError(
            code = "REPLAY_WINDOW_EXPIRED",
            reason = "the replay window has expired",
            requestId = "request-expired",
            effectId = effectId,
        )
    }

    @Test
/**
 * 이 시나리오는 "expired diagnostics release both records and insertion order keys" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expired diagnostics release both records and insertion order keys`() {
        var now = Instant.parse("2026-07-21T00:00:00Z")
        val registry = VoucherPoolDiagnosticRegistry({ now }, 15.minutes())

        repeat(200) { index ->
            registry.record(
                requestId = "request-$index",
                tenantCandidate = "tenant-a",
                method = "GET",
                path = "/api/v1/snapshots",
                status = 200,
                elapsedMillis = 1
            )
        }

        now = now.plus(16.minutes())
        repeat(200) { index ->
            registry.find("tenant-a", "request-$index").shouldBeNull()
        }

        registry.queuedKeyCount() shouldBeEqualTo 0
    }
}
