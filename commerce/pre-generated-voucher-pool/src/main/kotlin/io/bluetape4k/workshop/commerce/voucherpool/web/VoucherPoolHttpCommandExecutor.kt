@file:Suppress("LongParameterList", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.AllocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.MutationResult
import io.bluetape4k.workshop.commerce.voucherpool.application.RedeemVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.RedemptionService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReleaseAllocationCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ReplaceLostRevealCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReserveVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.RevealVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolLifecycleException
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCatalog
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.bluetape4k.workshop.commerce.voucherpool.query.AllocationReadModel
import io.bluetape4k.workshop.commerce.voucherpool.query.ReservationReadModel
import io.bluetape4k.workshop.commerce.voucherpool.query.VoucherPoolQueryService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID

/** customer HTTP precondition과 safe replay descriptor를 authoritative application service에 매핑합니다. */
@Component
internal class VoucherPoolHttpCommandExecutor(
    private val reservations: ReservationService,
    private val allocations: AllocationService,
    private val redemptions: RedemptionService,
    private val queries: VoucherPoolQueryService,
    private val admission: VoucherPoolAdmissionGate,
    private val digests: VoucherDigestService,
) {
    fun reserve(
        principal: TenantPrincipal,
        campaignId: UUID,
        idempotencyKey: String,
        ifNoneMatch: String,
        @Suppress("UNUSED_PARAMETER") request: ReserveVoucherRequest,
        requestId: String,
    ): ResponseEntity<ReservationResponse> = translate {
        requireCreatePrecondition(ifNoneMatch)
        admit(AdmissionNamespace.RESERVE, principal, campaignId)
        val result = reservations.reserve(
            ReserveVoucherCommand(
                tenantId = principal.tenantId,
                campaignId = campaignId,
                canonicalUser = principal.principalId,
                idempotencyKey = idempotencyKey,
            ),
        )
        reservationResult(principal, result, requestId, created = true)
    }

    fun reservation(
        principal: TenantPrincipal,
        reservationId: UUID,
        requestId: String,
    ): ResponseEntity<ReservationResponse> = translate {
        val snapshot = queries.reservation(principal.tenantId, principal.principalId, reservationId)
            ?: throw resourceNotFound()
        ResponseEntity.ok().eTag(etag(snapshot.revision)).body(snapshot.response(requestId))
    }

    fun allocate(
        principal: TenantPrincipal,
        reservationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        requestId: String,
    ): ResponseEntity<AllocationResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val reservation = queries.reservation(principal.tenantId, principal.principalId, reservationId)
            ?: throw resourceNotFound()
        admit(AdmissionNamespace.ALLOCATE, principal, reservation.campaignId)
        val result = allocations.allocate(
            AllocateVoucherCommand(
                tenantId = principal.tenantId,
                campaignId = reservation.campaignId,
                reservationId = reservationId,
                canonicalUser = principal.principalId,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        allocationResult(principal, result, requestId)
    }

    fun reveal(
        principal: TenantPrincipal,
        allocationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        requestId: String,
    ): ResponseEntity<RevealResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val allocation = ownedAllocation(principal, allocationId)
        admit(AdmissionNamespace.REVEAL, principal, allocation.campaignId)
        val result = allocations.reveal(
            RevealVoucherCommand(
                tenantId = principal.tenantId,
                campaignId = allocation.campaignId,
                allocationId = allocationId,
                canonicalUser = principal.principalId,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        revealResult(principal, allocationId, result, requestId)
    }

    fun allocation(
        principal: TenantPrincipal,
        allocationId: UUID,
        requestId: String,
    ): ResponseEntity<AllocationResponse> = translate {
        val snapshot = ownedAllocation(principal, allocationId)
        ResponseEntity.ok().eTag(etag(snapshot.revision)).body(snapshot.response(requestId))
    }

    fun replaceLostReveal(
        principal: TenantPrincipal,
        allocationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        @Suppress("UNUSED_PARAMETER") request: ReplaceLostRevealRequest,
        requestId: String,
    ): ResponseEntity<ReservationResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val allocation = ownedAllocation(principal, allocationId)
        admit(AdmissionNamespace.ALLOCATE, principal, allocation.campaignId)
        val result = allocations.replaceLostReveal(
            ReplaceLostRevealCommand(
                tenantId = principal.tenantId,
                campaignId = allocation.campaignId,
                allocationId = allocationId,
                canonicalUser = principal.principalId,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        reservationResult(principal, result, requestId, created = true)
    }

    fun redeem(
        principal: TenantPrincipal,
        allocationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        request: RedeemVoucherRequest,
        requestId: String,
    ): ResponseEntity<AllocationResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val allocation = ownedAllocation(principal, allocationId)
        admit(AdmissionNamespace.REDEEM, principal, allocation.campaignId)
        val code = CanonicalVoucherCode.of(request.code)
        val result = redemptions.redeem(
            RedeemVoucherCommand(
                tenantId = principal.tenantId,
                campaignId = allocation.campaignId,
                allocationId = allocationId,
                canonicalUser = principal.principalId,
                code = code,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        allocationResult(principal, result, requestId)
    }

    fun release(
        principal: TenantPrincipal,
        allocationId: UUID,
        idempotencyKey: String,
        expectedRevision: String,
        requestId: String,
    ): ResponseEntity<AllocationResponse> = translate {
        val revision = parseRevision(expectedRevision)
        val allocation = ownedAllocation(principal, allocationId)
        admit(AdmissionNamespace.ALLOCATE, principal, allocation.campaignId)
        val result = redemptions.release(
            ReleaseAllocationCommand(
                tenantId = principal.tenantId,
                campaignId = allocation.campaignId,
                allocationId = allocationId,
                canonicalUser = principal.principalId,
                expectedRevision = revision,
                idempotencyKey = idempotencyKey,
            ),
        )
        allocationResult(principal, result, requestId)
    }

    private fun reservationResult(
        principal: TenantPrincipal,
        result: MutationResult<io.bluetape4k.workshop.commerce.voucherpool.application.ReservationSnapshot>,
        requestId: String,
        created: Boolean,
    ): ResponseEntity<ReservationResponse> {
        val outcome = result.commandOutcome()
        val descriptor = outcome.descriptor
        val reservationId = outcome.value?.reservationId ?: checkNotNull(descriptor?.effectId)
        val read = queries.reservation(principal.tenantId, principal.principalId, reservationId)
            ?: throw resourceNotFound()
        val response = read.response(
            requestId = requestId,
            state = descriptor?.outcome?.removePrefix("RESERVATION_") ?: read.state.name,
            revision = descriptor?.revision ?: read.revision,
        )
        val builder: ResponseEntity.BodyBuilder =
            if (created && !outcome.replayed) {
                ResponseEntity.created(URI.create("/api/v1/reservations/$reservationId"))
            } else {
                ResponseEntity.status(HttpStatus.OK)
            }
        return builder.eTag(etag(response.revision)).commandHeaders(outcome.replayed).body(response)
    }

    private fun allocationResult(
        principal: TenantPrincipal,
        result: MutationResult<io.bluetape4k.workshop.commerce.voucherpool.application.AllocationSnapshot>,
        requestId: String,
    ): ResponseEntity<AllocationResponse> {
        val outcome = result.commandOutcome()
        val descriptor = outcome.descriptor
        val allocationId = outcome.value?.allocationId ?: checkNotNull(descriptor?.effectId)
        val read = queries.allocation(principal.tenantId, principal.principalId, allocationId)
            ?: throw resourceNotFound()
        val response = read.response(
            requestId = requestId,
            state = descriptor?.outcome?.removePrefix("ALLOCATION_") ?: read.state.name,
            revision = descriptor?.revision ?: read.revision,
        )
        return ResponseEntity.status(HttpStatus.OK)
            .eTag(etag(response.revision))
            .commandHeaders(outcome.replayed)
            .body(response)
    }

    private fun revealResult(
        principal: TenantPrincipal,
        allocationId: UUID,
        result: MutationResult<io.bluetape4k.workshop.commerce.voucherpool.application.RevealResult>,
        requestId: String,
    ): ResponseEntity<RevealResponse> {
        val outcome = result.commandOutcome()
        val read = ownedAllocation(principal, allocationId)
        val reveal = outcome.value
        val descriptor = outcome.descriptor
        val stableOutcome = reveal?.outcome ?: VoucherPoolErrorCode.ALREADY_REVEALED.name
        val code = reveal?.code?.withRawValue { it }
        val replacementAvailable =
            code == null &&
                stableOutcome == VoucherPoolErrorCode.ALREADY_REVEALED.name &&
                read.replacementOrdinal == 0 &&
                (queries.campaign(principal.tenantId, read.campaignId)?.replacementAllowance ?: 0) >= 1
        val response = RevealResponse(
            allocationId = allocationId,
            outcome = stableOutcome,
            revision = reveal?.revision ?: checkNotNull(descriptor?.revision),
            codeAvailable = code != null,
            code = code,
            observedAt = read.observedAt,
            requestId = requestId,
            safeRequestId = requestId,
            replacementAvailable = replacementAvailable,
            nextAction = when {
                code != null -> "COPY_ONCE_OR_REDEEM"
                replacementAvailable -> "CONFIRM_REPLACEMENT_OR_REFRESH"
                else -> "CONTACT_OPERATOR_WITH_REQUEST_ID"
            },
        )
        val duplicate = outcome.replayed || stableOutcome == VoucherPoolErrorCode.ALREADY_REVEALED.name
        return ResponseEntity.status(HttpStatus.OK)
            .eTag(etag(response.revision))
            .header("Pragma", "no-cache")
            .commandHeaders(duplicate)
            .body(response)
    }

    private fun ownedAllocation(principal: TenantPrincipal, allocationId: UUID): AllocationReadModel =
        queries.allocation(principal.tenantId, principal.principalId, allocationId) ?: throw resourceNotFound()

    private fun admit(namespace: AdmissionNamespace, principal: TenantPrincipal, campaignId: UUID) {
        val principalDigest = digests.userIdentity(principal.tenantId, campaignId, principal.principalId).copyBytes()
        val decision = try {
            admission.admit(namespace, principalDigest)
        } finally {
            principalDigest.fill(0)
        }
        when (decision) {
            AdmissionDecision.ALLOW,
            AdmissionDecision.DEGRADED_ALLOW,
            -> Unit
            AdmissionDecision.RATE_LIMITED -> throw apiFailure(VoucherPoolErrorCode.RATE_LIMITED)
            AdmissionDecision.DATABASE_BUSY -> throw apiFailure(VoucherPoolErrorCode.POOL_BUSY)
        }
    }

    private fun requireCreatePrecondition(ifNoneMatch: String) {
        if (ifNoneMatch != "*") throw invalidRequest()
    }

    private fun parseRevision(ifMatch: String): Long {
        val match = STRONG_ETAG.matchEntire(ifMatch) ?: throw invalidRequest()
        return match.groupValues[1].toLongOrNull() ?: throw invalidRequest()
    }

    private fun <T> MutationResult<T>.commandOutcome(): CommandOutcome<T> = when (this) {
        is MutationResult.Applied -> CommandOutcome(value, null, false)
        is MutationResult.Replay -> {
            descriptor.terminalCode?.let { throw apiFailure(it) }
            CommandOutcome(null, descriptor, true)
        }
        is MutationResult.Expired -> throw apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED, effectId)
    }

    // caller validation failure는 안전한 HTTP vocabulary로 의도적으로 normalize합니다.
    @Suppress("SwallowedException")
    private fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (failure: VoucherPoolApiException) {
            throw failure
        } catch (failure: VoucherPoolLifecycleException) {
            throw apiFailure(failure.code)
        } catch (_: VoucherPoolJdbcTimeoutException) {
            throw apiFailure(VoucherPoolErrorCode.BACKEND_TIMEOUT)
        } catch (_: IllegalArgumentException) {
            throw invalidRequest()
        }

    private class CommandOutcome<T>(
        val value: T?,
        val descriptor: SafeResponseDescriptor?,
        val replayed: Boolean,
    )

    private companion object {
        val STRONG_ETAG = Regex("\"(0|[1-9][0-9]*)\"")
    }
}

internal fun apiFailure(code: VoucherPoolErrorCode, effectId: UUID? = null): VoucherPoolApiException {
    if (code == VoucherPoolErrorCode.WRONG_OWNER || code == VoucherPoolErrorCode.SCOPE_NOT_FOUND) {
        return resourceNotFound()
    }
    val semantics = VoucherPoolErrorCatalog[code]
    return VoucherPoolApiException(
        stableCode = code.name,
        status = semantics.httpStatus,
        safeReason = code.safeReason(),
        retryAfterSeconds = if (semantics.retryable) 1L else null,
        effectId = effectId,
    )
}

private fun VoucherPoolErrorCode.safeReason(): String = SAFE_REASON_BY_CODE.getValue(this)

private val SAFE_REASON_BY_CODE =
    mapOf(
        VoucherPoolErrorCode.COMMAND_IN_PROGRESS to "command is still in progress",
        VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT to
            "idempotency key conflicts with another request",
        VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED to "the replay window has expired",
        VoucherPoolErrorCode.POOL_BUSY to "voucher pool is temporarily unavailable",
        VoucherPoolErrorCode.BACKEND_TIMEOUT to "voucher pool is temporarily unavailable",
        VoucherPoolErrorCode.POOL_EXHAUSTED to "voucher pool is exhausted",
        VoucherPoolErrorCode.USER_LIMIT_REACHED to "voucher limit has been reached",
        VoucherPoolErrorCode.STALE_REVISION to "resource revision is stale",
        VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE to "campaign is not available",
        VoucherPoolErrorCode.CAMPAIGN_PAUSED to "campaign is not available",
        VoucherPoolErrorCode.CAMPAIGN_REVOKING to "campaign is not available",
        VoucherPoolErrorCode.CAMPAIGN_REVOKED to "campaign is not available",
        VoucherPoolErrorCode.BATCH_PAUSED to "voucher batch is not available",
        VoucherPoolErrorCode.BATCH_EXPIRING to "voucher batch is not available",
        VoucherPoolErrorCode.BATCH_REVOKED to "voucher batch is not available",
        VoucherPoolErrorCode.BATCH_EXPIRED to "voucher batch is not available",
        VoucherPoolErrorCode.BATCH_FAILED_RETRYABLE to "voucher batch is not available",
        VoucherPoolErrorCode.BATCH_FAILED_TERMINAL to "voucher batch is not available",
        VoucherPoolErrorCode.RESERVATION_EXPIRED to "reservation has expired",
        VoucherPoolErrorCode.ALLOCATION_EXPIRED to "allocation has expired",
        VoucherPoolErrorCode.RATE_LIMITED to "request rate is limited",
        VoucherPoolErrorCode.KEY_MATERIAL_UNAVAILABLE to "voucher key material is unavailable",
        VoucherPoolErrorCode.CIPHERTEXT_INVALID to "voucher key material is unavailable",
        VoucherPoolErrorCode.ALREADY_REVEALED to "voucher code was already revealed",
        VoucherPoolErrorCode.WRONG_OWNER to "resource was not found",
        VoucherPoolErrorCode.SCOPE_NOT_FOUND to "resource was not found",
    ).also { reasons ->
        check(reasons.keys == VoucherPoolErrorCode.entries.toSet()) {
            "Customer HTTP safe reasons must cover every voucher pool error code."
        }
    }

private fun ReservationReadModel.response(
    requestId: String,
    state: String = this.state.name,
    revision: Long = this.revision,
): ReservationResponse = ReservationResponse(
    reservationId = reservationId,
    campaignId = campaignId,
    batchId = batchId,
    state = state,
    expiresAt = expiresAt,
    entitlementRootId = entitlementRootId,
    replacementOrdinal = replacementOrdinal,
    policyVersion = policyVersion,
    revision = revision,
    observedAt = observedAt,
    requestId = requestId,
    nextAction = state.reservationNextAction(),
)

private fun AllocationReadModel.response(
    requestId: String,
    state: String = this.state.name,
    revision: Long = this.revision,
): AllocationResponse = AllocationResponse(
    allocationId = allocationId,
    reservationId = reservationId,
    campaignId = campaignId,
    batchId = batchId,
    state = state,
    expiresAt = expiresAt,
    entitlementRootId = entitlementRootId,
    replacementOrdinal = replacementOrdinal,
    policyVersion = policyVersion,
    revision = revision,
    observedAt = observedAt,
    requestId = requestId,
    nextAction = state.allocationNextAction(),
)

private fun String.reservationNextAction(): String = when (this) {
    "ACTIVE" -> "ALLOCATE_OR_RELEASE"
    "ALLOCATED" -> "VIEW_ALLOCATION"
    else -> "CREATE_RESERVATION"
}

private fun String.allocationNextAction(): String = when (this) {
    "ALLOCATED" -> "REVEAL_REDEEM_OR_RELEASE"
    "REDEEMED" -> "COMPLETE"
    else -> "CREATE_RESERVATION"
}

private fun etag(revision: Long): String = "\"$revision\""

private fun ResponseEntity.BodyBuilder.commandHeaders(replayed: Boolean): ResponseEntity.BodyBuilder =
    header("Idempotency-Replay-Window", "86400").apply {
        if (replayed) header("Duplicate-Request", "true")
    }
