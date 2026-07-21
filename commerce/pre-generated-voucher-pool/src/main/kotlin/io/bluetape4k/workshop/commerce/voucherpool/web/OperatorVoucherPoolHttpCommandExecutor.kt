@file:Suppress("LongParameterList", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.workshop.commerce.voucherpool.application.BatchCommandException
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchCommandFailure
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchSourceKind
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateCampaignCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateImportBatchCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.GenerateChunkCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ImportChunkCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.MutationResult
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokeAggregateType
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokeCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokePreviewSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokeProgressSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.ReconciliationCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ReconciliationCommandException
import io.bluetape4k.workshop.commerce.voucherpool.application.ReconciliationCommandFailure
import io.bluetape4k.workshop.commerce.voucherpool.application.ReconciliationProgressSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.RevocationCommandException
import io.bluetape4k.workshop.commerce.voucherpool.application.RevocationCommandFailure
import io.bluetape4k.workshop.commerce.voucherpool.application.UpdateCampaignPolicyCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolRevocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolReconciliationCommandService
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.query.VoucherPoolQueryService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Maps operator HTTP preconditions to tenant-scoped campaign and batch commands. */
@Component
internal class OperatorVoucherPoolHttpCommandExecutor(
    private val service: CampaignBatchCommandService,
    private val queries: VoucherPoolQueryService,
    private val digests: VoucherDigestService,
    private val revocations: VoucherPoolRevocationService,
    private val reconciliations: VoucherPoolReconciliationCommandService,
) {
    fun createCampaign(
        tenantId: String,
        idempotencyKey: String,
        ifNoneMatch: String,
        request: OperatorCreateCampaignRequest,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> = translate {
        requireCreatePrecondition(ifNoneMatch)
        val result = service.createCampaign(
            CreateCampaignCommand(
                tenantId,
                request.campaignId,
                request.startsAt,
                request.endsAt,
                request.policy(),
                idempotencyKey,
            ),
        )
        campaignResult(tenantId, request.campaignId, result, requestId, created = true)
    }

    fun updateCampaignPolicy(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorCampaignPolicyRequest,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> = translate {
        val result = service.updatePolicy(
            UpdateCampaignPolicyCommand(
                tenantId,
                campaignId,
                parseRevision(ifMatch),
                request.policy(),
                idempotencyKey,
            ),
        )
        campaignResult(tenantId, campaignId, result, requestId)
    }

    fun activateCampaign(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> = translate {
        val result = service.activateCampaign(
            CampaignRevisionCommand(tenantId, campaignId, parseRevision(ifMatch), idempotencyKey),
        )
        campaignResult(tenantId, campaignId, result, requestId)
    }

    fun pauseCampaign(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> = campaignTransition(
        tenantId,
        campaignId,
        idempotencyKey,
        ifMatch,
        requestId,
        service::pauseCampaign,
    )

    fun resumeCampaign(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorCampaignResponse> = campaignTransition(
        tenantId,
        campaignId,
        idempotencyKey,
        ifMatch,
        requestId,
        service::resumeCampaign,
    )

    fun createBatch(
        tenantId: String,
        idempotencyKey: String,
        ifNoneMatch: String,
        request: OperatorCreateBatchRequest,
        imported: Boolean,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> = translate {
        requireCreatePrecondition(ifNoneMatch)
        if (!imported && request.codes.isNotEmpty()) throw invalidRequest()
        val sourceKind = if (imported) BatchSourceKind.IMPORTED else BatchSourceKind.GENERATED
        val manifest = request.manifestDigest.digestValue()
        val requestFingerprint = serverRequestFingerprint(tenantId, sourceKind, request)
        val result = service.createImportBatch(
            CreateImportBatchCommand(
                tenantId,
                request.batchId,
                request.campaignId,
                sourceKind,
                manifest,
                requestFingerprint,
                request.expectedCount,
                request.activatesAt,
                request.expiresAt,
                request.codes,
                idempotencyKey,
            ),
        )
        batchResult(tenantId, request.batchId, result, requestId, created = true)
    }

    fun importChunk(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorImportChunkRequest,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> = translate {
        val result = service.importChunk(
            ImportChunkCommand(
                tenantId,
                batchId,
                request.campaignId,
                request.firstOrdinal,
                request.manifestDigest.digestValue(),
                request.codes,
                parseRevision(ifMatch),
                idempotencyKey,
            ),
        )
        batchResult(tenantId, batchId, result, requestId)
    }

    fun generateChunk(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorGenerateChunkRequest,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> = translate {
        val result = service.generateChunk(
            GenerateChunkCommand(
                tenantId,
                batchId,
                request.campaignId,
                request.firstOrdinal,
                request.manifestDigest.digestValue(),
                request.count,
                parseRevision(ifMatch),
                idempotencyKey,
            ),
        )
        batchResult(tenantId, batchId, result, requestId)
    }

    fun activateBatch(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> = translate {
        val batch = queries.batch(tenantId, batchId) ?: throw resourceNotFound()
        val result = service.activateBatch(
            BatchRevisionCommand(tenantId, batch.campaignId, batchId, parseRevision(ifMatch), idempotencyKey),
        )
        batchResult(tenantId, batchId, result, requestId)
    }

    fun pauseBatch(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> =
        batchTransition(tenantId, batchId, idempotencyKey, ifMatch, requestId, service::pauseBatch)

    fun resumeBatch(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorBatchResponse> =
        batchTransition(tenantId, batchId, idempotencyKey, ifMatch, requestId, service::resumeBatch)

    fun previewCampaignRevoke(
        tenantId: String,
        campaignId: UUID,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorRevokePreviewResponse> = translate {
        val preview = revocations.preview(
            tenantId,
            RevokeAggregateType.CAMPAIGN,
            campaignId,
            parseRevision(ifMatch),
        )
        ResponseEntity.ok().eTag(operatorEtag(preview.revision)).body(preview.toResponse(requestId))
    }

    fun previewBatchRevoke(
        tenantId: String,
        batchId: UUID,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorRevokePreviewResponse> = translate {
        val preview = revocations.preview(tenantId, RevokeAggregateType.BATCH, batchId, parseRevision(ifMatch))
        ResponseEntity.ok().eTag(operatorEtag(preview.revision)).body(preview.toResponse(requestId))
    }

    fun revokeCampaign(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorCampaignRevokeRequest,
        requestId: String,
    ): ResponseEntity<OperatorWorkerProgressResponse> = revoke(
        tenantId,
        RevokeAggregateType.CAMPAIGN,
        campaignId,
        request.confirmedCampaignId,
        request.previewToken,
        idempotencyKey,
        ifMatch,
        requestId,
    )

    fun revokeBatch(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        request: OperatorBatchRevokeRequest,
        requestId: String,
    ): ResponseEntity<OperatorWorkerProgressResponse> = revoke(
        tenantId,
        RevokeAggregateType.BATCH,
        batchId,
        request.confirmedBatchId,
        request.previewToken,
        idempotencyKey,
        ifMatch,
        requestId,
    )

    private fun revoke(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        confirmedAggregateId: UUID,
        previewToken: String,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
    ): ResponseEntity<OperatorWorkerProgressResponse> = translate {
        val result = revocations.revoke(
            RevokeCommand(
                tenantId,
                aggregateType,
                aggregateId,
                confirmedAggregateId,
                parseRevision(ifMatch),
                previewToken,
                idempotencyKey,
            ),
        )
        val outcome = result.commandOutcome()
        val progress = when (result) {
            is MutationResult.Applied -> result.value
            is MutationResult.Replay -> revocations.progress(tenantId, aggregateType, aggregateId)
                ?: throw resourceNotFound()
            is MutationResult.Expired -> throw apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED, result.effectId)
        }
        val builder = ResponseEntity.status(if (outcome.replayed) HttpStatus.OK else HttpStatus.ACCEPTED)
        builder.eTag(operatorEtag(progress.revision))
            .operatorCommandHeaders(outcome.replayed)
            .body(progress.toResponse(requestId))
    }

    fun runReconciliation(
        tenantId: String,
        idempotencyKey: String,
        request: OperatorReconciliationRequest,
        requestId: String,
    ): ResponseEntity<OperatorReconciliationProgressResponse> = translate {
        val result = reconciliations.run(ReconciliationCommand(tenantId, request.batchId, idempotencyKey))
        val outcome = result.commandOutcome()
        val progress = when (result) {
            is MutationResult.Applied -> result.value
            is MutationResult.Replay -> reconciliations.progress(tenantId, request.batchId) ?: throw resourceNotFound()
            is MutationResult.Expired -> throw apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED, result.effectId)
        }
        ResponseEntity.status(if (outcome.replayed) HttpStatus.OK else HttpStatus.ACCEPTED)
            .operatorCommandHeaders(outcome.replayed)
            .body(progress.toResponse(requestId))
    }

    private fun campaignTransition(
        tenantId: String,
        campaignId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
        transition: (CampaignRevisionCommand) -> MutationResult<CampaignSnapshot>,
    ): ResponseEntity<OperatorCampaignResponse> = translate {
        val result = transition(
            CampaignRevisionCommand(tenantId, campaignId, parseRevision(ifMatch), idempotencyKey),
        )
        campaignResult(tenantId, campaignId, result, requestId)
    }

    private fun batchTransition(
        tenantId: String,
        batchId: UUID,
        idempotencyKey: String,
        ifMatch: String,
        requestId: String,
        transition: (BatchRevisionCommand) -> MutationResult<BatchSnapshot>,
    ): ResponseEntity<OperatorBatchResponse> = translate {
        val batch = queries.batch(tenantId, batchId) ?: throw resourceNotFound()
        val result = transition(
            BatchRevisionCommand(tenantId, batch.campaignId, batchId, parseRevision(ifMatch), idempotencyKey),
        )
        batchResult(tenantId, batchId, result, requestId)
    }

    private fun campaignResult(
        tenantId: String,
        campaignId: UUID,
        result: MutationResult<CampaignSnapshot>,
        requestId: String,
        created: Boolean = false,
    ): ResponseEntity<OperatorCampaignResponse> {
        val outcome = result.commandOutcome()
        val read = queries.campaign(tenantId, campaignId) ?: throw resourceNotFound()
        val response = read.toOperatorResponse(requestId)
        val builder = if (created && !outcome.replayed) {
            ResponseEntity.created(URI.create("/operator/api/v1/campaigns/$campaignId"))
        } else {
            ResponseEntity.status(HttpStatus.OK)
        }
        return builder.eTag(operatorEtag(response.revision)).operatorCommandHeaders(outcome.replayed).body(response)
    }

    private fun batchResult(
        tenantId: String,
        batchId: UUID,
        result: MutationResult<BatchSnapshot>,
        requestId: String,
        created: Boolean = false,
    ): ResponseEntity<OperatorBatchResponse> {
        val outcome = result.commandOutcome()
        val read = queries.batch(tenantId, batchId) ?: throw resourceNotFound()
        val response = read.toOperatorResponse(requestId)
        val builder = if (created && !outcome.replayed) {
            ResponseEntity.created(URI.create("/operator/api/v1/batches/$batchId"))
        } else {
            ResponseEntity.status(HttpStatus.OK)
        }
        return builder.eTag(operatorEtag(response.revision)).operatorCommandHeaders(outcome.replayed).body(response)
    }

    private fun serverRequestFingerprint(
        tenantId: String,
        sourceKind: BatchSourceKind,
        request: OperatorCreateBatchRequest,
    ): DigestValue {
        val material = listOf(
            request.batchId,
            request.campaignId,
            sourceKind,
            request.manifestDigest.lowercase(),
            request.expectedCount,
            request.activatesAt,
            request.expiresAt ?: "",
            request.codes.size,
        ).joinToString("|")
        val bytes = digests.audit(tenantId, "operator-batch-create", material).copyBytes()
        return try {
            DigestValue.of(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun <T> MutationResult<T>.commandOutcome(): CommandOutcome = when (this) {
        is MutationResult.Applied -> CommandOutcome(false)
        is MutationResult.Replay -> {
            descriptor.terminalCode?.let { throw apiFailure(it) }
            CommandOutcome(true)
        }
        is MutationResult.Expired -> throw apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED, effectId)
    }

    @Suppress("SwallowedException")
    private fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (failure: VoucherPoolApiException) {
            throw failure
        } catch (failure: BatchCommandException) {
            throw failure.toApiException()
        } catch (failure: RevocationCommandException) {
            throw failure.toApiException()
        } catch (failure: ReconciliationCommandException) {
            throw failure.toApiException()
        } catch (_: IllegalArgumentException) {
            throw invalidRequest()
        }

    private class CommandOutcome(val replayed: Boolean)

}

private fun OperatorCreateCampaignRequest.policy(): VoucherPoolPolicy =
    VoucherPoolPolicy.of(
        perUserLimit,
        reservationTtlSeconds.seconds,
        allocationTtlSeconds.seconds,
        replacementAllowance,
    )

private fun OperatorCampaignPolicyRequest.policy(): VoucherPoolPolicy =
    VoucherPoolPolicy.of(
        perUserLimit,
        reservationTtlSeconds.seconds,
        allocationTtlSeconds.seconds,
        replacementAllowance,
    )

private fun requireCreatePrecondition(ifNoneMatch: String) {
    if (ifNoneMatch != "*") throw invalidRequest()
}

private fun parseRevision(ifMatch: String): Long {
    val match = OPERATOR_STRONG_ETAG.matchEntire(ifMatch) ?: throw invalidRequest()
    return match.groupValues[1].toLongOrNull() ?: throw invalidRequest()
}

private fun String.digestValue(): DigestValue {
    if (!matches(HEX_DIGEST)) throw invalidRequest()
    val bytes = ByteArray(length / HEX_OCTET_CHARACTERS) { index ->
        val offset = index * HEX_OCTET_CHARACTERS
        substring(offset, offset + HEX_OCTET_CHARACTERS).toInt(HEX_RADIX).toByte()
    }
    return try {
        DigestValue.of(bytes)
    } finally {
        bytes.fill(0)
    }
}

private fun BatchCommandException.toApiException(): VoucherPoolApiException = when (reason) {
    BatchCommandFailure.SCOPE_NOT_FOUND -> resourceNotFound()
    BatchCommandFailure.STALE_REVISION -> apiFailure(VoucherPoolErrorCode.STALE_REVISION)
    BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT,
    BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT,
    BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT,
    -> apiFailure(VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT)
    BatchCommandFailure.COMMAND_IN_PROGRESS -> apiFailure(VoucherPoolErrorCode.COMMAND_IN_PROGRESS)
    BatchCommandFailure.REPLAY_WINDOW_EXPIRED -> apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED)
    BatchCommandFailure.RETRYABLE_INTEGRITY_COLLISION -> apiFailure(VoucherPoolErrorCode.POOL_BUSY)
    BatchCommandFailure.DUPLICATE_CODE,
    BatchCommandFailure.VALIDATION_REJECTED,
    -> apiFailure(VoucherPoolErrorCode.BATCH_FAILED_TERMINAL)
    BatchCommandFailure.INVALID_STATE,
    BatchCommandFailure.ACTIVATION_INCOMPLETE,
    -> VoucherPoolApiException("INVALID_STATE", HTTP_CONFLICT, "resource state does not permit this operation")
    BatchCommandFailure.ORDINAL_GAP -> invalidRequest()
}

private fun RevocationCommandException.toApiException(): VoucherPoolApiException = when (reason) {
    RevocationCommandFailure.SCOPE_NOT_FOUND -> resourceNotFound()
    RevocationCommandFailure.STALE_REVISION -> apiFailure(VoucherPoolErrorCode.STALE_REVISION)
    RevocationCommandFailure.COMMAND_IN_PROGRESS -> apiFailure(VoucherPoolErrorCode.COMMAND_IN_PROGRESS)
    RevocationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT ->
        apiFailure(VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT)
    RevocationCommandFailure.REPLAY_WINDOW_EXPIRED -> apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED)
    RevocationCommandFailure.INVALID_STATE ->
        VoucherPoolApiException("INVALID_STATE", HTTP_CONFLICT, "resource state does not permit this operation")
    RevocationCommandFailure.INVALID_PREVIEW -> invalidRequest()
}

private fun ReconciliationCommandException.toApiException(): VoucherPoolApiException = when (reason) {
    ReconciliationCommandFailure.SCOPE_NOT_FOUND -> resourceNotFound()
    ReconciliationCommandFailure.COMMAND_IN_PROGRESS -> apiFailure(VoucherPoolErrorCode.COMMAND_IN_PROGRESS)
    ReconciliationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT ->
        apiFailure(VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT)
    ReconciliationCommandFailure.REPLAY_WINDOW_EXPIRED -> apiFailure(VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED)
}

private fun RevokePreviewSnapshot.toResponse(requestId: String) = OperatorRevokePreviewResponse(
    aggregateType,
    aggregateId,
    revision,
    counts,
    eligibleDepth,
    activeReservations,
    activeAllocations,
    alreadyTerminalCount,
    affectedCount,
    previewToken,
    expiresAt,
    observedAt,
    requestId,
)

private fun RevokeProgressSnapshot.toResponse(requestId: String) = OperatorWorkerProgressResponse(
    aggregateType,
    aggregateId,
    state,
    revision,
    workerCount,
    observedAt,
    requestId,
)

private fun ReconciliationProgressSnapshot.toResponse(requestId: String) = OperatorReconciliationProgressResponse(
    kind,
    scopeId,
    state,
    cursor,
    checkpoint,
    attempt,
    nextAction,
    revision,
    observedAt,
    requestId,
)

private fun operatorEtag(revision: Long): String = "\"$revision\""

private fun ResponseEntity.BodyBuilder.operatorCommandHeaders(replayed: Boolean): ResponseEntity.BodyBuilder =
    header("Idempotency-Replay-Window", "86400").apply {
        if (replayed) header("Duplicate-Request", "true")
    }

private val HEX_DIGEST = Regex("[0-9a-fA-F]{64}")
private val OPERATOR_STRONG_ETAG = Regex("\"(0|[1-9][0-9]*)\"")
private const val HEX_OCTET_CHARACTERS = 2
private const val HEX_RADIX = 16
private const val HTTP_CONFLICT = 409
