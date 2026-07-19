package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.workshop.commerce.voucher.application.CampaignCommandService
import io.bluetape4k.workshop.commerce.voucher.application.CampaignPolicyCommand
import io.bluetape4k.workshop.commerce.voucher.application.CampaignTransitionCommand
import io.bluetape4k.workshop.commerce.voucher.application.ClaimCommandService
import io.bluetape4k.workshop.commerce.voucher.application.ClaimTransitionCommand
import io.bluetape4k.workshop.commerce.voucher.application.CreateCampaignCommand
import io.bluetape4k.workshop.commerce.voucher.application.ReviewCommandService
import io.bluetape4k.workshop.commerce.voucher.application.ReviewDecisionCommand
import io.bluetape4k.workshop.commerce.voucher.application.RetryableVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.digestHex
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.query.VoucherQueryService
import io.bluetape4k.workshop.commerce.voucher.reconciliation.ReconciliationResult
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationWorker
import io.bluetape4k.workshop.commerce.voucher.reconciliation.WorkerRunResult
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

internal data class CreateCampaignRequest(
    val campaignId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    @field:Min(1) @field:Max(1_000_000) val capacity: Int,
    @field:Min(1) @field:Max(1_000) val perUserLimit: Int,
    @field:Positive val redemptionTtlSeconds: Long,
)

internal data class ExpectedRevisionRequest(
    @field:Min(0) val expectedRevision: Long,
)

internal data class CampaignPolicyRequest(
    @field:Min(0) val expectedRevision: Long,
    @field:Min(1) @field:Max(1_000_000) val capacity: Int,
    @field:Min(1) @field:Max(1_000) val perUserLimit: Int,
    @field:Positive val redemptionTtlSeconds: Long,
)

internal data class ReviewDecisionHttpRequest(
    val campaignId: UUID,
    val claimId: UUID,
    @field:Min(0) val expectedReviewRevision: Long,
    @field:Min(0) val expectedClaimRevision: Long,
)

internal data class RevokeClaimRequest(
    val campaignId: UUID,
    @field:Min(0) val expectedRevision: Long,
)

internal data class ReconciliationHttpResponse(
    val processed: Int,
    val skipped: Int,
    val failed: Int,
    val cursor: String?,
    val deadlineReached: Boolean,
)

/** Live operator API behind [OperatorAccessFilter] and route-specific preconditions. */
@RestController
@RequestMapping("/operator/api/v1")
internal class OperatorVoucherController(
    private val campaigns: CampaignCommandService,
    private val claims: ClaimCommandService,
    private val reviews: ReviewCommandService,
    private val queries: VoucherQueryService,
    private val executor: VoucherHttpCommandExecutor,
    private val reconciliation: VoucherReconciliationWorker,
) {
    @PostMapping("/campaigns")
    fun createCampaign(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
        @Valid @RequestBody body: CreateCampaignRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        if (ifNoneMatch != "*") throw invalidRequest("If-None-Match must be *")
        val executed =
            executor.execute(
                tenant,
                OPERATOR_PRINCIPAL,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "CAMPAIGN_CREATE",
                body.campaignId,
                listOf(body.campaignId, body.startsAt, body.endsAt, body.capacity, body.perUserLimit, body.redemptionTtlSeconds)
                    .joinToString("\u0000"),
            ) {
                val created =
                    campaigns.create(
                        CreateCampaignCommand(
                            tenant,
                            body.campaignId,
                            body.startsAt,
                            body.endsAt,
                            body.capacity,
                            body.perUserLimit,
                            body.redemptionTtlSeconds,
                        ),
                    )
                created.storedCampaignResponse(
                    kind = VoucherResponseKind.CAMPAIGN_CREATED,
                    status = 201,
                    publicHeaders = mapOf("Location" to "/api/v1/campaigns/${created.campaignId}"),
                )
            }
        return executedResponse(executed, request, executed.response::campaignBody)
    }

    @PostMapping("/campaigns/{campaignId}/activate")
    fun activateCampaign(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ExpectedRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        return campaignTransition(
            campaignId,
            tenantHeader,
            idempotencyHeader,
            body.expectedRevision,
            "CAMPAIGN_ACTIVATE",
            VoucherResponseKind.CAMPAIGN_ACTIVATED,
            request,
            campaigns::activate,
        )
    }

    @PostMapping("/campaigns/{campaignId}/pause")
    fun pauseCampaign(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ExpectedRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> =
        campaignTransition(
            campaignId,
            tenantHeader,
            idempotencyHeader,
            body.expectedRevision,
            "CAMPAIGN_PAUSE",
            VoucherResponseKind.CAMPAIGN_PAUSE_ACCEPTED,
            request,
            campaigns::pause,
        )

    @PostMapping("/campaigns/{campaignId}/end")
    fun endCampaign(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ExpectedRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> =
        campaignTransition(
            campaignId,
            tenantHeader,
            idempotencyHeader,
            body.expectedRevision,
            "CAMPAIGN_END",
            VoucherResponseKind.CAMPAIGN_END_ACCEPTED,
            request,
            campaigns::end,
        )

    @PostMapping("/campaigns/{campaignId}/policy")
    fun updatePolicy(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: CampaignPolicyRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val executed =
            executor.execute(
                tenant,
                OPERATOR_PRINCIPAL,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "CAMPAIGN_POLICY",
                campaignId,
                listOf(body.expectedRevision, body.capacity, body.perUserLimit, body.redemptionTtlSeconds)
                    .joinToString("\u0000"),
            ) {
                val updated =
                    campaigns.updatePolicy(
                        CampaignPolicyCommand(
                            tenant,
                            campaignId,
                            body.expectedRevision,
                            body.capacity,
                            body.perUserLimit,
                            body.redemptionTtlSeconds,
                        ),
                    )
                updated.storedCampaignResponse(
                    kind = VoucherResponseKind.CAMPAIGN_POLICY_UPDATED,
                    status = 200,
                    publicHeaders = mapOf("ETag" to "\"${updated.revision}\""),
                )
            }
        return executedResponse(executed, request, executed.response::campaignBody)
    }

    @PostMapping("/reviews/{reviewId}/approve")
    fun approveReview(
        @PathVariable reviewId: Long,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ReviewDecisionHttpRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> = reviewDecision(reviewId, tenantHeader, idempotencyHeader, body, true, request)

    @PostMapping("/reviews/{reviewId}/reject")
    fun rejectReview(
        @PathVariable reviewId: Long,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ReviewDecisionHttpRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> = reviewDecision(reviewId, tenantHeader, idempotencyHeader, body, false, request)

    @PostMapping("/claims/{claimId}/revoke")
    fun revokeClaim(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: RevokeClaimRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val executed =
            executor.execute(
                tenant,
                OPERATOR_PRINCIPAL,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "CLAIM_REVOKE",
                claimId,
                "${body.campaignId}\u0000${body.expectedRevision}",
            ) {
                val updated =
                    claims.revoke(ClaimTransitionCommand(tenant, body.campaignId, claimId, body.expectedRevision))
                updated.storedClaimResponse(VoucherResponseKind.CLAIM_REVOCATION_ACCEPTED)
            }
        return executedResponse(executed, request, executed.response::claimBody)
    }

    @PostMapping("/reconciliation/run")
    fun runReconciliation(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: EmptyOperatorRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val resourceId = UUID.nameUUIDFromBytes("$tenant\u0000reconciliation".toByteArray(UTF_8))
        val executed =
            executor.executeExternal(
                tenant,
                OPERATOR_PRINCIPAL,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                "RECONCILIATION_RUN",
                resourceId,
                "manual",
            ) {
                when (val result = reconciliation.runManual()) {
                    is WorkerRunResult.Manual -> result.result.storedReconciliation(resourceId)
                    WorkerRunResult.LocalRunInProgress ->
                        throw RetryableVoucherCommand(
                            StoredHttpResponse(
                                VoucherResponseKind.RECONCILIATION_IN_PROGRESS,
                                409,
                                mapOf("Retry-After" to "1"),
                                resourceId,
                                null,
                                0,
                                null,
                                null,
                            ),
                        )
                    else -> error("manual reconciliation returned an unsupported worker result")
                }
            }
        return executedResponse(executed, request) { executed.response.reconciliationBody() }
    }

    private fun campaignTransition(
        campaignId: UUID,
        tenantHeader: String?,
        idempotencyHeader: String?,
        expectedRevision: Long,
        operation: String,
        responseKind: VoucherResponseKind,
        request: HttpServletRequest,
        transition: (CampaignTransitionCommand) -> CampaignSnapshot,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val executed =
            executor.execute(
                tenant,
                OPERATOR_PRINCIPAL,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                operation,
                campaignId,
                expectedRevision.toString(),
            ) {
                val updated = transition(CampaignTransitionCommand(tenant, campaignId, expectedRevision))
                updated.storedCampaignResponse(
                    kind = responseKind,
                    status = 200,
                    publicHeaders = mapOf("ETag" to "\"${updated.revision}\""),
                )
            }
        return executedResponse(executed, request, executed.response::campaignBody)
    }

    private fun reviewDecision(
        reviewId: Long,
        tenantHeader: String?,
        idempotencyHeader: String?,
        body: ReviewDecisionHttpRequest,
        approved: Boolean,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val operation = if (approved) "REVIEW_APPROVE" else "REVIEW_REJECT"
        val executed =
            executor.execute(
                tenant,
                OPERATOR_PRINCIPAL,
                idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
                operation,
                body.claimId,
                listOf(reviewId, body.campaignId, body.expectedReviewRevision, body.expectedClaimRevision)
                    .joinToString("\u0000"),
            ) {
                val command =
                    ReviewDecisionCommand(
                        tenant,
                        body.campaignId,
                        body.claimId,
                        reviewId,
                        body.expectedReviewRevision,
                        body.expectedClaimRevision,
                        digestHex("voucher-operator-actor-v1", tenant, OPERATOR_PRINCIPAL),
                    )
                val result = if (approved) reviews.approve(command) else reviews.reject(command)
                result.claim.storedClaimResponse(
                    if (approved) VoucherResponseKind.REVIEW_APPROVED else VoucherResponseKind.REVIEW_REJECTED,
                )
            }
        return executedResponse(executed, request, executed.response::claimBody)
    }

    companion object {
        private const val OPERATOR_PRINCIPAL = "workshop-operator"
    }
}

internal class EmptyOperatorRequest

private fun ReconciliationResult.storedReconciliation(resourceId: UUID): StoredHttpResponse =
    StoredHttpResponse(
        VoucherResponseKind.RECONCILIATION_COMPLETED,
        200,
        mapOf(RECONCILIATION_DESCRIPTOR_HEADER to encodeReconciliation()),
        resourceId,
        null,
        0,
        null,
        null,
    )

private fun ReconciliationResult.encodeReconciliation(): String =
    listOf(processed, skipped, failed, lastCursor ?: "-", if (deadlineReached) 1 else 0).joinToString(":")

private fun StoredHttpResponse.reconciliationBody(): ReconciliationHttpResponse {
    val values = requireNotNull(headers[RECONCILIATION_DESCRIPTOR_HEADER]).split(':')
    require(values.size == 5) { "invalid stored reconciliation descriptor" }
    return ReconciliationHttpResponse(
        processed = values[0].toInt(),
        skipped = values[1].toInt(),
        failed = values[2].toInt(),
        cursor = values[3].takeUnless { it == "-" },
        deadlineReached = values[4] == "1",
    )
}

@RestController
internal class VoucherRuntimeController {
    @GetMapping("/internal/runtime-thread")
    fun runtime(): Map<String, Any> =
        mapOf(
            "virtual" to Thread.currentThread().isVirtual,
            "javaFeature" to Runtime.version().feature(),
        )
}
