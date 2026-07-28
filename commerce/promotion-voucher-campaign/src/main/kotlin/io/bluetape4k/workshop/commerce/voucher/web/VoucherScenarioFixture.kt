package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherScenarioDefinition
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherScenarioFixtures
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherFixtureResetResult
import io.bluetape4k.workshop.commerce.voucher.fixture.VoucherFixtureResetService
import io.bluetape4k.workshop.commerce.voucher.fixture.FixtureRiskOperation
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.reconciliation.DelayedVoucherEvent
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationService
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

/** loopback demo와 integration-test profile에서만 사용하는 server-owned deterministic signal입니다. */
@Component
@Profile("local", "demo", "test")
internal class VoucherScenarioFixture(
    private val scenarios: VoucherScenarioFixtures,
    private val resetter: VoucherFixtureResetService,
    private val reconciliation: VoucherReconciliationService,
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
        campaignId: UUID?,
    ): VoucherFixtureExecution {
        val definition = scenarios.configure(scenario, tenantId, principalRef)
        val evidence =
            if (scenario == "delayed-duplicate-out-of-order") {
                val aggregateId = campaignId ?: throw IllegalArgumentException("campaignId is required for delayed events")
                val eventId = UUID.nameUUIDFromBytes("$tenantId\u0000$aggregateId\u0000delayed".toByteArray(UTF_8))
                val applied = DelayedVoucherEvent(tenantId, eventId, "CAMPAIGN", aggregateId, "a".repeat(64), 1)
                listOf(
                    reconciliation.accept(applied).outcome.name,
                    reconciliation.accept(applied).outcome.name,
                    reconciliation.accept(
                        DelayedVoucherEvent(
                            tenantId,
                            UUID.nameUUIDFromBytes("$tenantId\u0000$aggregateId\u0000stale".toByteArray(UTF_8)),
                            "CAMPAIGN",
                            aggregateId,
                            "b".repeat(64),
                            0,
                        ),
                    ).outcome.name,
                )
            } else {
                emptyList()
            }
        log.info { "voucher_fixture_configured scenario=${definition.slug}" }
        return VoucherFixtureExecution(definition, evidence)
    }

    fun signalFor(
        tenantId: String,
        principalRef: String,
        operation: FixtureRiskOperation,
    ): RiskSignal? = scenarios.consumeRiskSignal(tenantId, principalRef, operation)

    fun definition(scenario: String): VoucherScenarioDefinition = scenarios.definition(scenario)

    fun reset(tenantId: String): VoucherFixtureResetResult = resetter.reset(tenantId)

    companion object : KLogging()
}

internal data class VoucherFixtureExecution(
    val definition: VoucherScenarioDefinition,
    val evidence: List<String>,
)

internal data class VoucherFixtureRequest(
    @field:NotBlank @field:Size(max = 64) val principalRef: String,
    val campaignId: UUID? = null,
)

internal data class VoucherFixtureResponse(
    val scenario: String,
    val principalRef: String,
    val executionMode: String,
    val preparedNextOperation: String?,
    val evidence: List<String>,
    val expected: io.bluetape4k.workshop.commerce.voucher.fixture.VoucherScenarioExpectation,
)

internal data class VoucherFixtureResetResponse(val clearedSignals: Int, val deletedRows: Int)

/** guarded fixture API입니다. production profile에는 controller 전체가 없습니다. */
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
        val resourceId =
            UUID.nameUUIDFromBytes(
                "$tenant\u0000$principal\u0000$scenario\u0000${body.campaignId ?: "-"}".toByteArray(UTF_8),
            )
        var configured: VoucherFixtureExecution? = null
        val executed =
            executor.execute(
                tenant,
                FIXTURE_PRINCIPAL,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "FIXTURE_ALLOCATION_REVIEW",
                resourceId,
                principal,
            ) {
                val execution = fixture.configure(scenario, tenant, principal, body.campaignId)
                configured = execution
                StoredHttpResponse(
                    VoucherResponseKind.FIXTURE_CONFIGURED,
                    200,
                    mapOf(FIXTURE_EVIDENCE_DESCRIPTOR_HEADER to execution.evidence.joinToString(",")),
                    resourceId,
                    null,
                    0,
                    null,
                    null,
                )
            }
        return executedResponse(executed, request) {
            val definition = configured?.definition ?: fixture.definition(scenario)
            val evidence =
                executed.response.headers[FIXTURE_EVIDENCE_DESCRIPTOR_HEADER]
                    .orEmpty()
                    .split(',')
                    .filter(String::isNotBlank)
            VoucherFixtureResponse(
                scenario,
                principal,
                when {
                    scenario == "delayed-duplicate-out-of-order" -> "SERVER_EVENT"
                    definition.riskOperation != null -> "SERVER_SIGNAL"
                    else -> "CLIENT_CHOREOGRAPHY"
                },
                definition.riskOperation?.name,
                evidence,
                definition.expected,
            )
        }
    }

    @PostMapping("/reset")
    fun reset(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val resourceId = UUID.nameUUIDFromBytes("$tenant\u0000fixture-reset".toByteArray(UTF_8))
        var resetResult: VoucherFixtureResetResult? = null
        val executed =
            executor.execute(
                tenant,
                FIXTURE_PRINCIPAL,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "FIXTURE_RESET",
                resourceId,
                tenant,
            ) {
                resetResult = fixture.reset(tenant)
                val result = requireNotNull(resetResult)
                StoredHttpResponse(
                    VoucherResponseKind.FIXTURE_CONFIGURED,
                    200,
                    mapOf(FIXTURE_RESET_DESCRIPTOR_HEADER to "${result.clearedSignals}:${result.deletedRows}"),
                    resourceId,
                    null,
                    0,
                    null,
                    null,
                )
            }
        return executedResponse(executed, request) {
            val values = requireNotNull(executed.response.headers[FIXTURE_RESET_DESCRIPTOR_HEADER]).split(':')
            VoucherFixtureResetResponse(values[0].toInt(), values[1].toInt())
        }
    }

    private companion object {
        const val FIXTURE_PRINCIPAL = "workshop-fixture"
        const val FIXTURE_EVIDENCE_DESCRIPTOR_HEADER = "X-Workshop-Fixture-Evidence"
        const val FIXTURE_RESET_DESCRIPTOR_HEADER = "X-Workshop-Fixture-Reset-Result"
    }
}
