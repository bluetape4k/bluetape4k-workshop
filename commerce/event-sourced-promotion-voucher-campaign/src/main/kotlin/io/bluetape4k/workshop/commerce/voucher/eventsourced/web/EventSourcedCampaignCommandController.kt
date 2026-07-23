package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.CampaignCommandExecution
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.CreateCampaignCommandInput
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedCampaignCommands
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

internal const val IDEMPOTENCY_HEADER = "Idempotency-Key"
private const val MAX_CAMPAIGN_CAPACITY = 1_000_000L
private const val MAX_PER_USER_LIMIT = 1_000L

internal data class CreateCampaignHttpRequest(
    val campaignId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    @field:Min(1) @field:Max(MAX_CAMPAIGN_CAPACITY) val capacity: Int,
    @field:Min(1) @field:Max(MAX_PER_USER_LIMIT) val perUserLimit: Int,
    @field:Positive val redemptionTtlSeconds: Long,
)

internal class CampaignCommandHttpService(
    private val commands: EventSourcedCampaignCommands,
    private val snapshots: CampaignProjectionSnapshotReader,
) {
    fun create(
        tenant: String,
        principal: String,
        idempotencyKey: String,
        body: CreateCampaignHttpRequest,
    ): CampaignCreateHttpResult {
        val execution =
            commands.create(
                CreateCampaignCommandInput(
                    tenant = tenant,
                    principal = principal,
                    idempotencyKey = idempotencyKey,
                    campaignId = body.campaignId,
                    startsAt = body.startsAt,
                    endsAt = body.endsAt,
                    capacity = body.capacity,
                    perUserLimit = body.perUserLimit,
                    redemptionTtlSeconds = body.redemptionTtlSeconds,
                ),
            )
        return execution.toHttp(tenant, body)
    }

    private fun CampaignCommandExecution.toHttp(
        tenant: String,
        body: CreateCampaignHttpRequest,
    ): CampaignCreateHttpResult =
        when (this) {
            is CampaignCommandExecution.Completed -> {
                if (descriptor.outcome != ReceiptOutcome.CAMPAIGN_CREATED) {
                    CampaignCreateHttpResult.Conflict("CONCURRENT_MODIFICATION")
                } else {
                    val streamPosition = checkNotNull(descriptor.streamPosition)
                    val projectionPosition =
                        snapshots
                            .read(TenantId(tenant), body.campaignId)
                            .positions.projectionPosition
                            .coerceAtMost(streamPosition)
                    CampaignCreateHttpResult.Completed(
                        campaign =
                            CampaignHttpResponse(
                                campaignId = body.campaignId,
                                state = "DRAFT",
                                revision = 1,
                                policyVersion = 1,
                                capacity = body.capacity,
                                allocatedCount = 0,
                                remainingCapacity = body.capacity,
                                startsAt = body.startsAt,
                                endsAt = body.endsAt,
                                observedAt = checkNotNull(descriptor.observedAt),
                            ),
                        positions = ProjectionPositions(streamPosition, projectionPosition),
                        replayed = replayed,
                    )
                }
            }

            CampaignCommandExecution.FingerprintConflict ->
                CampaignCreateHttpResult.Conflict("IDEMPOTENCY_FINGERPRINT_CONFLICT")
            CampaignCommandExecution.InProgress -> CampaignCreateHttpResult.InProgress
            CampaignCommandExecution.KeyUnavailable -> CampaignCreateHttpResult.KeyUnavailable
        }
}

internal sealed interface CampaignCreateHttpResult {
    data class Completed(
        val campaign: CampaignHttpResponse,
        val positions: ProjectionPositions,
        val replayed: Boolean,
    ) : CampaignCreateHttpResult

    data class Conflict(val code: String) : CampaignCreateHttpResult

    data object InProgress : CampaignCreateHttpResult

    data object KeyUnavailable : CampaignCreateHttpResult
}

@RestController
@RequestMapping("/operator/api/v1")
internal class EventSourcedCampaignCommandController(
    private val service: CampaignCommandHttpService,
) {
    @PostMapping("/campaigns")
    fun create(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        @Valid @RequestBody body: CreateCampaignHttpRequest,
    ): ResponseEntity<Any> {
        val tenant = tenantHeader.requireNotNull(TENANT_HEADER).requireNotBlank(TENANT_HEADER)
        val principal =
            principalHeader.requireNotNull(PRINCIPAL_HEADER).requireNotBlank(PRINCIPAL_HEADER)
        val idempotencyKey =
            idempotencyHeader.requireNotNull(IDEMPOTENCY_HEADER)
                .requireNotBlank(IDEMPOTENCY_HEADER)
        ifNoneMatch.requireNotNull(HttpHeaders.IF_NONE_MATCH).requireEquals("*", HttpHeaders.IF_NONE_MATCH)
        return service.create(tenant, principal, idempotencyKey, body).toResponse()
    }
}

private fun CampaignCreateHttpResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is CampaignCreateHttpResult.Completed ->
            ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/v1/campaigns/${campaign.campaignId}")
                .header(STREAM_POSITION_HEADER, positions.streamPosition.toString())
                .header(PROJECTION_POSITION_HEADER, positions.projectionPosition.toString())
                .header(PROJECTION_LAG_HEADER, positions.lag.toString())
                .header("X-Idempotent-Replay", replayed.toString())
                .body(campaign)

        is CampaignCreateHttpResult.Conflict ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(EventSourcedApiError(code, "command conflicts with current state"))

        CampaignCreateHttpResult.InProgress ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(EventSourcedApiError("COMMAND_IN_PROGRESS", "command is already in progress"))

        CampaignCreateHttpResult.KeyUnavailable ->
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(EventSourcedApiError("REPLAY_KEY_UNAVAILABLE", "command replay key is unavailable"))
    }
