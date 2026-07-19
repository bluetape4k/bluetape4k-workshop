package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucher.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.AllocationService
import io.bluetape4k.workshop.commerce.voucher.application.AcknowledgeVoucherCodeCommand
import io.bluetape4k.workshop.commerce.voucher.application.ClaimCommandService
import io.bluetape4k.workshop.commerce.voucher.application.ClaimTransitionCommand
import io.bluetape4k.workshop.commerce.voucher.application.RedeemVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCodeAcknowledgementService
import io.bluetape4k.workshop.commerce.voucher.admission.RiskSignalService
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.query.VoucherQueryService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

internal data class AllocateClaimRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val userRef: String,
)

internal data class AllocationHttpResponse(
    val claimId: UUID,
    val state: String,
    val revision: Long,
    val policyVersion: Long,
    val expiresAt: Instant?,
    val reviewId: Long?,
    val code: String?,
)

internal data class CampaignHttpResponse(
    val campaignId: UUID,
    val state: String,
    val revision: Long,
    val policyVersion: Long,
    val capacity: Int,
    val allocatedCount: Int,
    val remainingCapacity: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val observedAt: Instant,
)

internal data class ClaimHttpResponse(
    val campaignId: UUID,
    val claimId: UUID,
    val state: String,
    val revision: Long,
    val policyVersion: Long,
    val expiresAt: Instant?,
)

internal data class RedeemClaimRequest(
    @field:NotBlank @field:Size(max = 64) val code: String,
    @field:jakarta.validation.constraints.Min(0) val expectedRevision: Long,
    @field:NotBlank @field:Size(max = 128) val redemptionReference: String,
)

internal data class ClaimRevisionRequest(
    @field:jakarta.validation.constraints.Min(0) val expectedRevision: Long,
)

internal data class VoucherCodeHttpResponse(
    val claimId: UUID,
    val state: String,
    val revision: Long,
    val code: String,
)

/** Live customer API; GET operations never reconstruct or disclose voucher codes. */
@RestController
@RequestMapping("/api/v1")
internal class CustomerVoucherController(
    private val allocation: AllocationService,
    private val claims: ClaimCommandService,
    private val acknowledgements: VoucherCodeAcknowledgementService,
    private val queries: VoucherQueryService,
    private val executor: VoucherHttpCommandExecutor,
    private val risks: RiskSignalService,
    private val fixtureProvider: ObjectProvider<VoucherScenarioFixture>,
) {
    @PostMapping("/campaigns/{campaignId}/claims")
    fun allocate(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: AllocateClaimRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        if (body.userRef != principal) throw invalidRequest("userRef must match X-Workshop-Principal")
        val executed =
            executor.execute(
                tenantId = tenant,
                principalRef = principal,
                rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                operation = "ALLOCATE",
                resourceId = campaignId,
                fingerprintMaterial = body.userRef,
            ) {
                val riskSignal =
                    fixtureProvider.ifAvailable?.signalFor(tenant, principal)
                        ?: risks.assess(tenant, principal)
                val result =
                    allocation.allocate(
                        AllocateVoucherCommand(tenant, campaignId, body.userRef, riskSignal),
                    )
                val descriptor = checkNotNull(queries.descriptor(tenant, result.claim.claimId))
                result.claim.storedAllocationResponse(
                    kind =
                        if (result.reviewId == null) {
                            VoucherResponseKind.ALLOCATION_ACCEPTED
                        } else {
                            VoucherResponseKind.ALLOCATION_REVIEW_REQUIRED
                        },
                    status = if (result.reviewId == null) 201 else 202,
                    allocationId = result.allocationId,
                    reviewId = result.reviewId,
                    generationKeyVersion = descriptor.generationKeyVersion,
                    verificationKeyVersion = descriptor.verificationKeyVersion,
                )
            }
        return executedResponse(executed, request) { allocationResponse(tenant, executed) }
    }

    @PostMapping("/claims/{claimId}/redeem")
    fun redeem(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: RedeemClaimRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        requireOwnedClaim(tenant, principal, claimId)
        val executed =
            executor.execute(
                tenant,
                principal,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "REDEEM",
                claimId,
                listOf(body.code, body.expectedRevision, body.redemptionReference).joinToString("\u0000"),
            ) {
                val result =
                    claims.redeem(
                        RedeemVoucherCommand(
                            tenantId = tenant,
                            code = body.code,
                            expectedRevision = body.expectedRevision,
                            redemptionReference = body.redemptionReference,
                            claimId = claimId,
                        ),
                    )
                result.storedClaimResponse(VoucherResponseKind.REDEMPTION_ACCEPTED)
            }
        return executedResponse(executed, request, executed.response::claimBody)
    }

    @PostMapping("/claims/{claimId}/release")
    fun release(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ClaimRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        val owned = requireOwnedClaim(tenant, principal, claimId)
        val executed =
            executor.execute(
                tenant,
                principal,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "RELEASE",
                claimId,
                body.expectedRevision.toString(),
            ) {
                val result =
                    claims.release(ClaimTransitionCommand(tenant, owned.campaignId, claimId, body.expectedRevision))
                result.storedClaimResponse(VoucherResponseKind.CLAIM_RELEASED)
            }
        return executedResponse(executed, request, executed.response::claimBody)
    }

    @PostMapping("/claims/{claimId}/code-acknowledgements")
    fun acknowledgeCode(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ClaimRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        val owned = requireOwnedClaim(tenant, principal, claimId)
        val executed =
            executor.execute(
                tenant,
                principal,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "CODE_ACKNOWLEDGEMENT",
                claimId,
                body.expectedRevision.toString(),
            ) {
                val result =
                    acknowledgements.acknowledge(
                        AcknowledgeVoucherCodeCommand(tenant, owned.campaignId, claimId, body.expectedRevision),
                    )
                val descriptor = checkNotNull(queries.descriptor(tenant, claimId))
                result.claim.storedClaimResponse(
                    kind = VoucherResponseKind.CODE_ACKNOWLEDGED,
                    allocationId = descriptor.allocationId,
                    generationKeyVersion = descriptor.generationKeyVersion,
                    verificationKeyVersion = descriptor.verificationKeyVersion,
                )
            }
        return executedResponse(executed, request) {
            val code = queries.activeCode(tenant, claimId)
                ?: throw VoucherApiException(
                    "IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE",
                    503,
                    "voucher replay material is unavailable",
                )
            val snapshot = executed.response.claimBody()
            VoucherCodeHttpResponse(snapshot.claimId, snapshot.state, snapshot.revision, code)
        }
    }

    @GetMapping("/campaigns/{campaignId}")
    fun campaign(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
    ): CampaignHttpResponse {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        val snapshot = queries.campaign(tenant, campaignId)
            ?: throw VoucherApiException("CAMPAIGN_NOT_FOUND", 404, "campaign was not found")
        return snapshot.toHttp()
    }

    @GetMapping("/claims/{claimId}")
    fun claim(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
    ): ClaimHttpResponse {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        val snapshot = queries.claimOwned(tenant, principal, claimId)
            ?: throw VoucherApiException("CLAIM_NOT_FOUND", 404, "claim was not found")
        return snapshot.toHttp()
    }

    private fun allocationResponse(
        tenantId: String,
        executed: ExecutedHttpCommand,
    ): AllocationHttpResponse {
        val code =
            if (executed.response.responseKind == VoucherResponseKind.ALLOCATION_ACCEPTED) {
                queries.activeCode(tenantId, executed.response.aggregateId)
                    ?: throw VoucherApiException(
                        "IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE",
                        503,
                        "voucher replay material is unavailable",
                    )
            } else {
                null
            }
        log.info {
            "voucher_allocation_http_completed claimId=${executed.response.aggregateId} " +
                "kind=${executed.response.responseKind} replayed=${executed.replayed}"
        }
        return executed.response.allocationBody(code)
    }

    private fun requireOwnedClaim(
        tenantId: String,
        principalRef: String,
        claimId: UUID,
    ): ClaimSnapshot =
        queries.claimOwned(tenantId, principalRef, claimId)
            ?: throw VoucherApiException("CLAIM_NOT_FOUND", 404, "claim was not found")

    companion object : KLogging()
}

internal fun CampaignSnapshot.toHttp(): CampaignHttpResponse =
    CampaignHttpResponse(
        campaignId = campaignId,
        state = state.name,
        revision = revision,
        policyVersion = policyVersion,
        capacity = capacity,
        allocatedCount = allocatedCount,
        remainingCapacity = (capacity - allocatedCount).coerceAtLeast(0),
        startsAt = startsAt,
        endsAt = endsAt,
        observedAt = Instant.now(),
    )

internal fun ClaimSnapshot.toHttp(): ClaimHttpResponse =
    ClaimHttpResponse(campaignId, claimId, state.name, revision, allocationPolicyVersion, expiresAt)
