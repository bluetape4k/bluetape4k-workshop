package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherScenarioDefinition
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherScenarioFixtures
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/** Server-owned deterministic signals used only by loopback demo and integration-test profiles. */
@Component
@Profile("local", "demo", "test")
internal class VoucherScenarioFixture(
    private val scenarios: VoucherScenarioFixtures,
) {

    fun configureAllocationReview(
        tenantId: String,
        principalRef: String,
    ) {
        scenarios.configure("allocation-review", tenantId, principalRef)
        log.info { "voucher_fixture_configured scenario=ALLOCATION_REVIEW" }
    }

    fun configure(
        scenario: String,
        tenantId: String,
        principalRef: String,
    ): VoucherScenarioDefinition =
        scenarios.configure(scenario, tenantId, principalRef).also {
            log.info { "voucher_fixture_configured scenario=${it.slug}" }
        }

    fun signalFor(
        tenantId: String,
        principalRef: String,
    ): RiskSignal? = scenarios.consumeRiskSignal(tenantId, principalRef)

    companion object : KLogging()
}

internal data class VoucherFixtureRequest(
    @field:NotBlank @field:Size(max = 64) val principalRef: String,
)

internal data class VoucherFixtureResponse(
    val scenario: String,
    val principalRef: String,
    val expected: io.bluetape4k.workshop.commerce.voucher.fixture.VoucherScenarioExpectation,
)

/** Guarded fixture API; the entire controller is absent from production profiles. */
@RestController
@Profile("local", "demo", "test")
@RequestMapping("/operator/api/v1/fixtures")
internal class VoucherFixtureController(
    private val fixture: VoucherScenarioFixture,
    private val executor: VoucherHttpCommandExecutor,
) {
    @PostMapping("/{scenario}/run")
    fun run(
        @PathVariable scenario: String,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: VoucherFixtureRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val principal = requireAsciiIdentifier(body.principalRef, "principalRef")
        val resourceId = UUID.nameUUIDFromBytes("$tenant\u0000$principal\u0000$scenario".toByteArray(UTF_8))
        var configured: VoucherScenarioDefinition? = null
        val executed =
            executor.execute(
                tenant,
                FIXTURE_PRINCIPAL,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "FIXTURE_ALLOCATION_REVIEW",
                resourceId,
                principal,
            ) {
                configured = fixture.configure(scenario, tenant, principal)
                StoredHttpResponse(
                    VoucherResponseKind.FIXTURE_CONFIGURED,
                    200,
                    emptyMap(),
                    resourceId,
                    null,
                    0,
                    null,
                    null,
                )
            }
        return executedResponse(executed, request) {
            val definition = configured ?: fixture.configure(scenario, tenant, principal)
            VoucherFixtureResponse(scenario, principal, definition.expected)
        }
    }

    private companion object {
        const val FIXTURE_PRINCIPAL = "workshop-fixture"
    }
}
