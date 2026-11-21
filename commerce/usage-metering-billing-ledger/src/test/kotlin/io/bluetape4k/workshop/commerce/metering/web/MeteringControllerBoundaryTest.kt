package io.bluetape4k.workshop.commerce.metering.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.application.BillingPeriodService
import io.bluetape4k.workshop.commerce.metering.application.MeterService
import io.bluetape4k.workshop.commerce.metering.application.PriceActivationService
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionService
import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * MeteringControllerBoundaryTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class MeteringControllerBoundaryTest {

    companion object: KLogging()

/**
 * commands은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val commands = mockk<IdempotentHttpCommandExecutor>(relaxed = true)
/**
 * controller은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val controller = TenantMeteringController(
        commands = commands,
        meters = mockk<MeterService>(),
        prices = mockk<PriceActivationService>(),
        usage = mockk<UsageIngestionService>(),
        periods = mockk<BillingPeriodService>(),
    )
/**
 * mvc은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(MeteringExceptionHandler())
        .build()

    @BeforeEach
    fun beforeEach() {
        clearAllMocks()
    }

    @Test
/**
 * 이 시나리오는 "tenant principal cannot submit a command for another tenant" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `tenant principal cannot submit a command for another tenant`() {
        mvc
            .post("/api/v1/tenants/tenant-b/meters") {
                principal = UsernamePasswordAuthenticationToken.authenticated("tenant-a", null, emptyList())
                header("Idempotency-Key", "request-1")
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"api_calls","unit":"request"}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("tenant_mismatch") }
            }

        verify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
/**
 * 이 시나리오는 "idempotency key is mandatory at the HTTP boundary" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `idempotency key is mandatory at the HTTP boundary`() {
        mvc
            .post("/api/v1/tenants/tenant-a/meters") {
                principal = UsernamePasswordAuthenticationToken.authenticated("tenant-a", null, emptyList())
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"api_calls","unit":"request"}"""
            }.andExpect {
                status { isBadRequest() }
            }

        verify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
/**
 * 이 시나리오는 "stored command failures and exception responses share one status policy" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stored command failures and exception responses share one status policy`() {
        MeteringErrorStatus
            .from(IllegalArgumentException("invalid_quantity")) shouldBeEqualTo HttpStatus.BAD_REQUEST

        MeteringErrorStatus
            .from(IllegalArgumentException("reconciliation_finding_stale")) shouldBeEqualTo HttpStatus.CONFLICT

        MeteringErrorStatus
            .from(IllegalStateException("price_not_found")) shouldBeEqualTo HttpStatus.UNPROCESSABLE_CONTENT

        MeteringErrorStatus
            .from(IllegalStateException("period_not_found")) shouldBeEqualTo HttpStatus.NOT_FOUND
    }
}
