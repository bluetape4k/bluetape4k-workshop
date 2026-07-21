@file:Suppress("LongParameterList", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokeAggregateType
import io.bluetape4k.workshop.commerce.voucherpool.query.BatchReadModel
import io.bluetape4k.workshop.commerce.voucherpool.query.CampaignReadModel
import io.bluetape4k.workshop.commerce.voucherpool.query.PoolDepthReadModel
import io.bluetape4k.workshop.commerce.voucherpool.query.StuckReservationCursor
import io.bluetape4k.workshop.commerce.voucherpool.query.StuckReservationPage
import io.bluetape4k.workshop.commerce.voucherpool.query.StuckReservationReadModel
import io.bluetape4k.workshop.commerce.voucherpool.query.VoucherPoolQueryService
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import java.io.Serializable
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID

@RestController
@RequestMapping("/operator/api/v1")
internal class OperatorVoucherPoolController(
    private val queries: VoucherPoolQueryService,
    private val commands: OperatorVoucherPoolHttpCommandExecutor,
    private val diagnostics: VoucherPoolDiagnosticRegistry,
    private val streams: VoucherPoolEventStream,
) {
    @PostMapping("/campaigns")
    fun createCampaign(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-None-Match") ifNoneMatch: String,
        @RequestBody body: OperatorCreateCampaignRequest,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorCampaignResponse> =
        commands.createCampaign(tenantId, idempotencyKey, ifNoneMatch, body, request.requestId())

    @PostMapping("/campaigns/{campaignId}/policy")
    fun updateCampaignPolicy(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable campaignId: UUID,
        @RequestBody body: OperatorCampaignPolicyRequest,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorCampaignResponse> =
        commands.updateCampaignPolicy(tenantId, campaignId, idempotencyKey, ifMatch, body, request.requestId())

    @PostMapping("/campaigns/{campaignId}/activate")
    fun activateCampaign(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable campaignId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorCampaignResponse> =
        commands.activateCampaign(tenantId, campaignId, idempotencyKey, ifMatch, request.requestId())

    @PostMapping("/campaigns/{campaignId}/pause")
    fun pauseCampaign(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable campaignId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorCampaignResponse> =
        commands.pauseCampaign(tenantId, campaignId, idempotencyKey, ifMatch, request.requestId())

    @PostMapping("/campaigns/{campaignId}/resume")
    fun resumeCampaign(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable campaignId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorCampaignResponse> =
        commands.resumeCampaign(tenantId, campaignId, idempotencyKey, ifMatch, request.requestId())

    @PostMapping("/campaigns/{campaignId}/revoke-preview")
    fun previewCampaignRevoke(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable campaignId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorRevokePreviewResponse> =
        commands.previewCampaignRevoke(tenantId, campaignId, ifMatch, request.requestId())

    @PostMapping("/campaigns/{campaignId}/revoke")
    fun revokeCampaign(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable campaignId: UUID,
        @RequestBody body: OperatorCampaignRevokeRequest,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorWorkerProgressResponse> =
        commands.revokeCampaign(tenantId, campaignId, idempotencyKey, ifMatch, body, request.requestId())

    @PostMapping("/batches/import")
    fun createImportBatch(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-None-Match") ifNoneMatch: String,
        @RequestBody body: OperatorCreateBatchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorBatchResponse> =
        commands.createBatch(
            tenantId,
            idempotencyKey,
            ifNoneMatch,
            body,
            imported = true,
            requestId = request.requestId(),
        )

    @PostMapping("/batches/generate")
    fun createGeneratedBatch(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-None-Match") ifNoneMatch: String,
        @RequestBody body: OperatorCreateBatchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorBatchResponse> =
        commands.createBatch(
            tenantId,
            idempotencyKey,
            ifNoneMatch,
            body,
            imported = false,
            requestId = request.requestId(),
        )

    @PostMapping("/batches/{batchId}/import-chunks")
    fun importChunk(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable batchId: UUID,
        @RequestBody body: OperatorImportChunkRequest,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorBatchResponse> =
        commands.importChunk(tenantId, batchId, idempotencyKey, ifMatch, body, request.requestId())

    @PostMapping("/batches/{batchId}/generate-chunks")
    fun generateChunk(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable batchId: UUID,
        @RequestBody body: OperatorGenerateChunkRequest,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorBatchResponse> =
        commands.generateChunk(tenantId, batchId, idempotencyKey, ifMatch, body, request.requestId())

    @PostMapping("/batches/{batchId}/activate")
    fun activateBatch(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable batchId: UUID,
        @RequestBody(required = false) body: Map<String, String>?,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorBatchResponse> {
        if (!body.isNullOrEmpty()) throw invalidRequest()
        return commands.activateBatch(tenantId, batchId, idempotencyKey, ifMatch, request.requestId())
    }

    @PostMapping("/batches/{batchId}/pause")
    fun pauseBatch(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable batchId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorBatchResponse> =
        commands.pauseBatch(tenantId, batchId, idempotencyKey, ifMatch, request.requestId())

    @PostMapping("/batches/{batchId}/resume")
    fun resumeBatch(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable batchId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorBatchResponse> =
        commands.resumeBatch(tenantId, batchId, idempotencyKey, ifMatch, request.requestId())

    @PostMapping("/batches/{batchId}/revoke-preview")
    fun previewBatchRevoke(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable batchId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorRevokePreviewResponse> =
        commands.previewBatchRevoke(tenantId, batchId, ifMatch, request.requestId())

    @PostMapping("/batches/{batchId}/revoke")
    fun revokeBatch(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable batchId: UUID,
        @RequestBody body: OperatorBatchRevokeRequest,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorWorkerProgressResponse> =
        commands.revokeBatch(tenantId, batchId, idempotencyKey, ifMatch, body, request.requestId())

    @PostMapping("/reconciliation/run")
    fun runReconciliation(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody body: OperatorReconciliationRequest,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorReconciliationProgressResponse> =
        commands.runReconciliation(tenantId, idempotencyKey, body, request.requestId())

    @GetMapping("/batches/{batchId}")
    fun batch(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @PathVariable batchId: UUID,
        request: HttpServletRequest,
    ): OperatorBatchResponse =
        queries.batch(tenantId, batchId)?.toOperatorResponse(request.requestId()) ?: throw resourceNotFound()

    @GetMapping("/pool-depth")
    fun poolDepth(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestParam(required = false) campaignId: UUID?,
        @RequestParam(required = false) batchId: UUID?,
        request: HttpServletRequest,
    ): OperatorPoolDepthResponse =
        queries.poolDepth(tenantId, campaignId, batchId)?.toResponse(request.requestId())
            ?: throw resourceNotFound()

    @GetMapping("/reservations/stuck")
    fun stuckReservations(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestParam(required = false) campaignId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        request: HttpServletRequest,
    ): OperatorStuckReservationPageResponse {
        if (limit !in 1..MAX_STUCK_PAGE_SIZE) throw invalidRequest()
        return queries.stuckReservations(tenantId, campaignId, cursor?.decodeCursor(), limit)
            ?.toResponse(request.requestId())
            ?: throw resourceNotFound()
    }

    @GetMapping("/diagnostics/{requestId}")
    fun diagnostic(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @PathVariable requestId: String,
        request: HttpServletRequest,
    ): OperatorDiagnosticResponse = diagnostics.find(tenantId, requestId)
        ?.toResponse(request.requestId()) ?: throw resourceNotFound()

    @GetMapping("/snapshots")
    fun snapshots(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestParam(required = false) campaignId: UUID?,
        @RequestParam(required = false) batchId: UUID?,
        request: HttpServletRequest,
    ): VoucherPoolSnapshotResponse = streams.operatorSnapshot(
        tenantId,
        campaignId,
        batchId,
        request.requestId(),
    )

    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @RequestHeader(TENANT_HEADER) tenantId: String,
        @RequestParam(required = false) campaignId: UUID?,
        @RequestParam(required = false) batchId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
        response: HttpServletResponse,
    ) {
        val subscription = streams.openOperator(
            tenantId,
            campaignId,
            batchId,
            resolveEventCursor(cursor, lastEventId),
        )
        response.contentType = MediaType.TEXT_EVENT_STREAM_VALUE
        streams.write(subscription, response.outputStream)
    }
}

internal data class OperatorCreateCampaignRequest(
    val campaignId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val perUserLimit: Int,
    val reservationTtlSeconds: Long,
    val allocationTtlSeconds: Long,
    val replacementAllowance: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OperatorCampaignPolicyRequest(
    val perUserLimit: Int,
    val reservationTtlSeconds: Long,
    val allocationTtlSeconds: Long,
    val replacementAllowance: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OperatorCreateBatchRequest(
    val batchId: UUID,
    val campaignId: UUID,
    val manifestDigest: String,
    val expectedCount: Long,
    val activatesAt: Instant,
    val expiresAt: Instant? = null,
    val codes: List<String> = emptyList(),
) : Serializable {
    override fun toString(): String =
        "OperatorCreateBatchRequest(batchId=$batchId,campaignId=$campaignId,manifestDigest=[REDACTED]," +
            "expectedCount=$expectedCount,activatesAt=$activatesAt,expiresAt=$expiresAt,codes=[REDACTED])"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OperatorImportChunkRequest(
    val campaignId: UUID,
    val firstOrdinal: Long,
    val manifestDigest: String,
    val codes: List<String>,
) : Serializable {
    override fun toString(): String =
        "OperatorImportChunkRequest(campaignId=$campaignId,firstOrdinal=$firstOrdinal," +
            "manifestDigest=[REDACTED],codes=[REDACTED])"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OperatorGenerateChunkRequest(
    val campaignId: UUID,
    val firstOrdinal: Long,
    val manifestDigest: String,
    val count: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OperatorCampaignRevokeRequest(
    val previewToken: String,
    val confirmedCampaignId: UUID,
) : Serializable {
    override fun toString(): String =
        "OperatorCampaignRevokeRequest(previewToken=[REDACTED],confirmedCampaignId=$confirmedCampaignId)"

    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorBatchRevokeRequest(
    val previewToken: String,
    val confirmedBatchId: UUID,
) : Serializable {
    override fun toString(): String =
        "OperatorBatchRevokeRequest(previewToken=[REDACTED],confirmedBatchId=$confirmedBatchId)"

    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorRevokePreviewResponse(
    val aggregateType: RevokeAggregateType,
    val aggregateId: UUID,
    val revision: Long,
    val counts: Map<EntryState, Long>,
    val eligibleDepth: Long,
    val activeReservations: Long,
    val activeAllocations: Long,
    val alreadyTerminalCount: Long,
    val affectedCount: Long,
    val previewToken: String,
    val expiresAt: Instant,
    val observedAt: Instant,
    val requestId: String,
) : Serializable {
    override fun toString(): String =
        "OperatorRevokePreviewResponse(aggregateType=$aggregateType,aggregateId=$aggregateId,revision=$revision," +
            "counts=$counts,eligibleDepth=$eligibleDepth,activeReservations=$activeReservations," +
            "activeAllocations=$activeAllocations,alreadyTerminalCount=$alreadyTerminalCount," +
            "affectedCount=$affectedCount,previewToken=[REDACTED],expiresAt=$expiresAt," +
            "observedAt=$observedAt,requestId=$requestId)"

    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorWorkerProgressResponse(
    val aggregateType: RevokeAggregateType,
    val aggregateId: UUID,
    val state: String,
    val revision: Long,
    val workerCount: Int,
    val observedAt: Instant,
    val requestId: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorReconciliationRequest(
    val batchId: UUID,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorReconciliationProgressResponse(
    val kind: WorkerKind,
    val scopeId: UUID,
    val state: String,
    val cursor: Long,
    val checkpoint: Long,
    val attempt: Int,
    val nextAction: String,
    val revision: Long,
    val observedAt: Instant,
    val requestId: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorDiagnosticResponse(
    val targetRequestId: String,
    val method: String,
    val path: String,
    val status: Int,
    val elapsedMillis: Long,
    val observedAt: Instant,
    val requestId: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorCampaignResponse(
    val campaignId: UUID,
    val state: CampaignState,
    val startsAt: Instant,
    val endsAt: Instant,
    val perUserLimit: Int,
    val reservationTtlSeconds: Long,
    val allocationTtlSeconds: Long,
    val replacementAllowance: Int,
    val policyVersion: Long,
    val revision: Long,
    val nextAction: String,
    val observedAt: Instant,
    val requestId: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorBatchResponse(
    val batchId: UUID,
    val campaignId: UUID,
    val state: BatchState,
    val sourceKind: String,
    val activatesAt: Instant,
    val expiresAt: Instant?,
    val nextSourceOrdinal: Long,
    val expectedCount: Long,
    val acceptedCount: Long,
    val rejectedCount: Long,
    val lastFailureCode: String?,
    val revision: Long,
    val nextAction: String,
    val observedAt: Instant,
    val requestId: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorPoolDepthResponse(
    val campaignId: UUID?,
    val batchId: UUID?,
    val counts: Map<EntryState, Long>,
    val eligibleAvailable: Long,
    val expiredButNotTerminalized: Long,
    val observedAt: Instant,
    val requestId: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorStuckReservationResponse(
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val state: ReservationState,
    val expiresAt: Instant,
    val revision: Long,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorStuckReservationPageResponse(
    val items: List<OperatorStuckReservationResponse>,
    val nextCursor: String?,
    val observedAt: Instant,
    val requestId: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal fun BatchReadModel.toOperatorResponse(requestId: String) =
    OperatorBatchResponse(
        batchId,
        campaignId,
        state,
        sourceKind,
        activatesAt,
        expiresAt,
        nextSourceOrdinal,
        expectedCount,
        acceptedCount,
        rejectedCount,
        lastFailureCode,
        revision,
        nextAction,
        observedAt,
        requestId,
    )

internal fun CampaignReadModel.toOperatorResponse(requestId: String) =
    OperatorCampaignResponse(
        campaignId,
        state,
        startsAt,
        endsAt,
        perUserLimit,
        reservationTtlSeconds,
        allocationTtlSeconds,
        replacementAllowance,
        policyVersion,
        revision,
        when (state) {
            CampaignState.DRAFT -> "UPDATE_POLICY_OR_ACTIVATE"
            CampaignState.ACTIVE -> "PAUSE_OR_REVOKE"
            CampaignState.PAUSED -> "UPDATE_POLICY_RESUME_OR_REVOKE"
            CampaignState.REVOKING -> "WAIT_FOR_REVOCATION"
            CampaignState.REVOKED -> "COMPLETE"
        },
        observedAt,
        requestId,
    )

private fun PoolDepthReadModel.toResponse(requestId: String) =
    OperatorPoolDepthResponse(
        campaignId,
        batchId,
        EntryState.entries.associateWith { counts[it] ?: 0L },
        eligibleAvailable,
        expiredButNotTerminalized,
        observedAt,
        requestId,
    )

private fun StuckReservationPage.toResponse(requestId: String) =
    OperatorStuckReservationPageResponse(
        items.map(StuckReservationReadModel::toResponse),
        nextCursor?.encode(),
        observedAt,
        requestId,
    )

private fun StuckReservationReadModel.toResponse() =
    OperatorStuckReservationResponse(reservationId, campaignId, batchId, entryId, state, expiresAt, revision)

private fun VoucherPoolDiagnosticRecord.toResponse(requestId: String) = OperatorDiagnosticResponse(
    targetRequestId,
    method,
    path,
    status,
    elapsedMillis,
    observedAt,
    requestId,
)

private fun StuckReservationCursor.encode(): String {
    val bytes = ByteBuffer.allocate(CURSOR_BYTES)
        .putLong(expiresAt.epochSecond)
        .putInt(expiresAt.nano)
        .putLong(reservationId.mostSignificantBits)
        .putLong(reservationId.leastSignificantBits)
        .array()
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun String.decodeCursor(): StuckReservationCursor = try {
    val bytes = Base64.getUrlDecoder().decode(this)
    if (bytes.size != CURSOR_BYTES) throw invalidRequest()
    val buffer = ByteBuffer.wrap(bytes)
    StuckReservationCursor(
        Instant.ofEpochSecond(buffer.long, buffer.int.toLong()),
        UUID(buffer.long, buffer.long),
    )
} catch (failure: VoucherPoolApiException) {
    throw failure
} catch (_: RuntimeException) {
    throw invalidRequest()
}

private const val CURSOR_BYTES = 28
private const val MAX_STUCK_PAGE_SIZE = 100
