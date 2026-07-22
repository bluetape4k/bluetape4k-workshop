package io.bluetape4k.workshop.commerce.metering.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.metering.application.BillingPeriodService
import io.bluetape4k.workshop.commerce.metering.application.MeterService
import io.bluetape4k.workshop.commerce.metering.application.PriceActivationService
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionService
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class MeteringControllerBoundaryTest {
    private val commands = mockk<IdempotentHttpCommandExecutor>(relaxed = true)
    private val controller = TenantMeteringController(
        commands,
        mockk<MeterService>(),
        mockk<PriceActivationService>(),
        mockk<UsageIngestionService>(),
        mockk<BillingPeriodService>(),
    )
    private val mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(MeteringExceptionHandler())
        .build()

    @Test
    fun `tenant principal cannot submit a command for another tenant`() {
        mvc.post("/api/v1/tenants/tenant-b/meters") {
            principal = UsernamePasswordAuthenticationToken.authenticated("tenant-a", null, emptyList())
            header("Idempotency-Key", "request-1")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"code":"api_calls","unit":"request"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("tenant_mismatch") }
        }

        verify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
    fun `idempotency key is mandatory at the HTTP boundary`() {
        mvc.post("/api/v1/tenants/tenant-a/meters") {
            principal = UsernamePasswordAuthenticationToken.authenticated("tenant-a", null, emptyList())
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"code":"api_calls","unit":"request"}"""
        }.andExpect {
            status { isBadRequest() }
        }

        verify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
    fun `stored command failures and exception responses share one status policy`() {
        MeteringErrorStatus.from(IllegalArgumentException("invalid_quantity")) shouldBeEqualTo HttpStatus.BAD_REQUEST
        MeteringErrorStatus.from(IllegalArgumentException("reconciliation_finding_stale")) shouldBeEqualTo
            HttpStatus.CONFLICT
        MeteringErrorStatus.from(IllegalStateException("price_not_found")) shouldBeEqualTo
            HttpStatus.UNPROCESSABLE_CONTENT
        MeteringErrorStatus.from(IllegalStateException("period_not_found")) shouldBeEqualTo HttpStatus.NOT_FOUND
    }
}
