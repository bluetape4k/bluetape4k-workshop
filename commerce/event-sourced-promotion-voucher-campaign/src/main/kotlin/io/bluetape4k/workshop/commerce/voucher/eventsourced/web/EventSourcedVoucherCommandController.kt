package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.AllocateVoucherCommandInput
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedVoucherCommands
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedVoucherLifecycleCommands
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.RedeemVoucherCommandInput
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.ReleaseVoucherCommandInput
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.VoucherCommandExecution
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

private const val FINGERPRINT_CONFLICT_STATUS = 409
private const val VOUCHER_PUBLIC_REVISION_OFFSET = 2

internal data class AllocateVoucherHttpRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val userRef: String,
)

internal data class VoucherAllocationHttpResponse(
    val claimId: UUID,
    val state: String,
    val revision: Long,
    val policyVersion: Long,
    val expiresAt: Instant?,
    val reviewId: Long?,
    val code: String?,
)

internal data class RedeemVoucherHttpRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val code: String,
    @field:Min(0)
    val expectedRevision: Long,
    @field:NotBlank
    @field:Size(max = 128)
    val redemptionReference: String,
)

internal data class VoucherRevisionHttpRequest(
    @field:Min(0)
    val expectedRevision: Long,
)

internal data class VoucherClaimHttpResponse(
    val campaignId: UUID,
    val claimId: UUID,
    val state: String,
    val revision: Long,
    val policyVersion: Long,
    val expiresAt: Instant?,
)

internal class VoucherCommandHttpService(
    private val commands: EventSourcedVoucherCommands,
    private val lifecycle: EventSourcedVoucherLifecycleCommands,
    private val snapshots: CampaignProjectionSnapshotReader,
) {
    fun allocate(
        tenant: String,
        principal: String,
        idempotencyKey: String,
        campaignId: UUID,
        body: AllocateVoucherHttpRequest,
    ): VoucherAllocationHttpResult =
        commands
            .allocate(
                AllocateVoucherCommandInput(
                    tenant = tenant,
                    principal = principal,
                    idempotencyKey = idempotencyKey,
                    campaignId = campaignId,
                    userRef = body.userRef,
                ),
            ).toHttp(tenant, campaignId)

    fun redeem(
        tenant: String,
        principal: String,
        idempotencyKey: String,
        voucherId: UUID,
        body: RedeemVoucherHttpRequest,
    ): VoucherTransitionHttpResult =
        lifecycle
            .redeem(
                RedeemVoucherCommandInput(
                    tenant = tenant,
                    principal = principal,
                    idempotencyKey = idempotencyKey,
                    voucherId = voucherId,
                    code = body.code,
                    expectedRevision = body.expectedRevision,
                    redemptionReference = body.redemptionReference,
                ),
            ).toTransitionHttp(tenant)

    fun release(
        tenant: String,
        principal: String,
        idempotencyKey: String,
        voucherId: UUID,
        body: VoucherRevisionHttpRequest,
    ): VoucherTransitionHttpResult =
        lifecycle
            .release(
                ReleaseVoucherCommandInput(
                    tenant = tenant,
                    principal = principal,
                    idempotencyKey = idempotencyKey,
                    voucherId = voucherId,
                    expectedRevision = body.expectedRevision,
                ),
            ).toTransitionHttp(tenant)

    private fun VoucherCommandExecution.toHttp(
        tenant: String,
        campaignId: UUID,
    ): VoucherAllocationHttpResult =
        when (this) {
            is VoucherCommandExecution.Completed ->
                if (descriptor.outcome == ReceiptOutcome.VOUCHER_ALLOCATED) {
                    val allocationId = checkNotNull(descriptor.allocationId)
                    val voucher = checkNotNull(commands.voucher(tenant, allocationId))
                    val streamPosition = checkNotNull(descriptor.streamPosition)
                    val projectionPosition =
                        snapshots
                            .read(TenantId(tenant), campaignId)
                            .positions.projectionPosition
                            .coerceAtMost(streamPosition)
                    VoucherAllocationHttpResult.Allocated(
                        body =
                            VoucherAllocationHttpResponse(
                                claimId = allocationId,
                                state = voucher.state.name,
                                revision = voucher.version - VOUCHER_PUBLIC_REVISION_OFFSET,
                                policyVersion = voucher.policyVersion - 1,
                                expiresAt = voucher.expiresAt,
                                reviewId = null,
                                code =
                                    commands.voucherCode(
                                        tenant = tenant,
                                        campaignId = campaignId,
                                        allocationId = allocationId,
                                        keyVersion = checkNotNull(descriptor.generationKeyVersion),
                                    ),
                            ),
                        replayed = replayed,
                        positions = ProjectionPositions(streamPosition, projectionPosition),
                    )
                } else {
                    VoucherAllocationHttpResult.Rejected(descriptor.status, descriptor.outcome.toFailureCode())
                }

            VoucherCommandExecution.FingerprintConflict ->
                VoucherAllocationHttpResult.Rejected(
                    FINGERPRINT_CONFLICT_STATUS,
                    "IDEMPOTENCY_FINGERPRINT_CONFLICT",
                )
            VoucherCommandExecution.InProgress -> VoucherAllocationHttpResult.InProgress
            VoucherCommandExecution.KeyUnavailable -> VoucherAllocationHttpResult.KeyUnavailable
        }

    private fun VoucherCommandExecution.toTransitionHttp(tenant: String): VoucherTransitionHttpResult =
        when (this) {
            is VoucherCommandExecution.Completed ->
                if (descriptor.outcome in TRANSITION_SUCCESS_OUTCOMES) {
                    val voucherId = checkNotNull(descriptor.allocationId)
                    val voucher = checkNotNull(commands.voucher(tenant, voucherId))
                    val streamPosition = checkNotNull(descriptor.streamPosition)
                    val campaignId = checkNotNull(voucher.campaignId)
                    val projectionPosition =
                        snapshots
                            .read(TenantId(tenant), campaignId)
                            .positions.projectionPosition
                            .coerceAtMost(streamPosition)
                    VoucherTransitionHttpResult.Transitioned(
                        body =
                            VoucherClaimHttpResponse(
                                campaignId = campaignId,
                                claimId = voucherId,
                                state = voucher.state.name,
                                revision = voucher.version - VOUCHER_PUBLIC_REVISION_OFFSET,
                                policyVersion = voucher.policyVersion - 1,
                                expiresAt = voucher.expiresAt,
                            ),
                        replayed = replayed,
                        positions = ProjectionPositions(streamPosition, projectionPosition),
                    )
                } else {
                    VoucherTransitionHttpResult.Rejected(descriptor.status, descriptor.outcome.toFailureCode())
                }

            VoucherCommandExecution.FingerprintConflict ->
                VoucherTransitionHttpResult.Rejected(
                    FINGERPRINT_CONFLICT_STATUS,
                    "IDEMPOTENCY_FINGERPRINT_CONFLICT",
                )
            VoucherCommandExecution.InProgress -> VoucherTransitionHttpResult.InProgress
            VoucherCommandExecution.KeyUnavailable -> VoucherTransitionHttpResult.KeyUnavailable
        }

    private companion object {
        val TRANSITION_SUCCESS_OUTCOMES =
            setOf(ReceiptOutcome.VOUCHER_REDEEMED, ReceiptOutcome.VOUCHER_RELEASED)
    }
}

internal sealed interface VoucherAllocationHttpResult {
    data class Allocated(
        val body: VoucherAllocationHttpResponse,
        val replayed: Boolean,
        val positions: ProjectionPositions,
    ) : VoucherAllocationHttpResult

    data class Rejected(
        val status: Int,
        val code: String,
    ) : VoucherAllocationHttpResult

    data object InProgress : VoucherAllocationHttpResult

    data object KeyUnavailable : VoucherAllocationHttpResult
}

internal sealed interface VoucherTransitionHttpResult {
    data class Transitioned(
        val body: VoucherClaimHttpResponse,
        val replayed: Boolean,
        val positions: ProjectionPositions,
    ) : VoucherTransitionHttpResult

    data class Rejected(
        val status: Int,
        val code: String,
    ) : VoucherTransitionHttpResult

    data object InProgress : VoucherTransitionHttpResult

    data object KeyUnavailable : VoucherTransitionHttpResult
}

@RestController
@RequestMapping("/api/v1")
internal class EventSourcedVoucherCommandController(
    private val service: VoucherCommandHttpService,
) {
    @PostMapping("/campaigns/{campaignId}/claims")
    fun allocate(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: AllocateVoucherHttpRequest,
    ): ResponseEntity<Any> {
        val tenant = tenantHeader.requireNotNull(TENANT_HEADER).requireNotBlank(TENANT_HEADER)
        val principal = principalHeader.requireNotNull(PRINCIPAL_HEADER).requireNotBlank(PRINCIPAL_HEADER)
        val idempotencyKey =
            idempotencyHeader.requireNotNull(IDEMPOTENCY_HEADER)
                .requireNotBlank(IDEMPOTENCY_HEADER)
        return service.allocate(tenant, principal, idempotencyKey, campaignId, body).toResponse()
    }

    @PostMapping("/claims/{claimId}/redeem")
    fun redeem(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: RedeemVoucherHttpRequest,
    ): ResponseEntity<Any> {
        val tenant = tenantHeader.requireNotNull(TENANT_HEADER).requireNotBlank(TENANT_HEADER)
        val principal = principalHeader.requireNotNull(PRINCIPAL_HEADER).requireNotBlank(PRINCIPAL_HEADER)
        val idempotencyKey =
            idempotencyHeader.requireNotNull(IDEMPOTENCY_HEADER)
                .requireNotBlank(IDEMPOTENCY_HEADER)
        return service.redeem(tenant, principal, idempotencyKey, claimId, body).toResponse()
    }

    @PostMapping("/claims/{claimId}/release")
    fun release(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: VoucherRevisionHttpRequest,
    ): ResponseEntity<Any> {
        val tenant = tenantHeader.requireNotNull(TENANT_HEADER).requireNotBlank(TENANT_HEADER)
        val principal = principalHeader.requireNotNull(PRINCIPAL_HEADER).requireNotBlank(PRINCIPAL_HEADER)
        val idempotencyKey =
            idempotencyHeader.requireNotNull(IDEMPOTENCY_HEADER)
                .requireNotBlank(IDEMPOTENCY_HEADER)
        return service.release(tenant, principal, idempotencyKey, claimId, body).toResponse()
    }
}

private fun VoucherAllocationHttpResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is VoucherAllocationHttpResult.Allocated ->
            ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/v1/claims/${body.claimId}")
                .header("Idempotency-Replayed", replayed.toString())
                .header(STREAM_POSITION_HEADER, positions.streamPosition.toString())
                .header(PROJECTION_POSITION_HEADER, positions.projectionPosition.toString())
                .header(PROJECTION_LAG_HEADER, positions.lag.toString())
                .body(body)

        is VoucherAllocationHttpResult.Rejected ->
            ResponseEntity.status(status)
                .body(EventSourcedApiError(code, "voucher allocation was rejected"))

        VoucherAllocationHttpResult.InProgress ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(EventSourcedApiError("COMMAND_IN_PROGRESS", "command is already in progress"))

        VoucherAllocationHttpResult.KeyUnavailable ->
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(EventSourcedApiError("REPLAY_KEY_UNAVAILABLE", "command replay key is unavailable"))
    }

private fun VoucherTransitionHttpResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is VoucherTransitionHttpResult.Transitioned ->
            ResponseEntity.ok()
                .header("Idempotency-Replayed", replayed.toString())
                .header(STREAM_POSITION_HEADER, positions.streamPosition.toString())
                .header(PROJECTION_POSITION_HEADER, positions.projectionPosition.toString())
                .header(PROJECTION_LAG_HEADER, positions.lag.toString())
                .body(body)

        is VoucherTransitionHttpResult.Rejected ->
            ResponseEntity.status(status)
                .body(EventSourcedApiError(code, "voucher transition was rejected"))

        VoucherTransitionHttpResult.InProgress ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(EventSourcedApiError("COMMAND_IN_PROGRESS", "command is already in progress"))

        VoucherTransitionHttpResult.KeyUnavailable ->
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(EventSourcedApiError("REPLAY_KEY_UNAVAILABLE", "command replay key is unavailable"))
    }

private fun ReceiptOutcome.toFailureCode(): String =
    when (this) {
        ReceiptOutcome.CAMPAIGN_NOT_FOUND -> "CAMPAIGN_NOT_FOUND"
        ReceiptOutcome.CAMPAIGN_NOT_ACTIVE -> "CAMPAIGN_NOT_ACTIVE"
        ReceiptOutcome.CAMPAIGN_NOT_STARTED -> "CAMPAIGN_NOT_STARTED"
        ReceiptOutcome.CAMPAIGN_ENDED -> "CAMPAIGN_ENDED"
        ReceiptOutcome.CAPACITY_EXHAUSTED -> "CAPACITY_EXHAUSTED"
        ReceiptOutcome.PER_USER_LIMIT_REACHED -> "PER_USER_LIMIT_REACHED"
        ReceiptOutcome.VOUCHER_NOT_FOUND -> "CLAIM_NOT_FOUND"
        ReceiptOutcome.STALE_REVISION -> "STALE_REVISION"
        ReceiptOutcome.INVALID_VOUCHER_CODE -> "INVALID_CODE"
        ReceiptOutcome.VOUCHER_EXPIRED -> "VOUCHER_EXPIRED"
        ReceiptOutcome.INVALID_TRANSITION -> "INVALID_TRANSITION"
        ReceiptOutcome.CONCURRENT_MODIFICATION -> "CONCURRENT_MODIFICATION"
        else -> "DOMAIN_REJECTED"
    }
