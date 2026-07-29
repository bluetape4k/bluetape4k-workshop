package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

internal const val TENANT_HEADER = "X-Workshop-Tenant"
internal const val PRINCIPAL_HEADER = "X-Workshop-Principal"
internal const val MIN_STREAM_POSITION_HEADER = "X-Min-Stream-Position"
internal const val STREAM_POSITION_HEADER = "X-Stream-Position"
internal const val PROJECTION_POSITION_HEADER = "X-Projection-Position"
internal const val PROJECTION_LAG_HEADER = "X-Projection-Lag"

/** #534-compatible campaign body입니다. event-sourcing metadata는 의도적으로 header에 담습니다. */
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

internal data class ProjectionPositions(
    val streamPosition: Long,
    val projectionPosition: Long,
) {
    init {
        streamPosition.requireZeroOrPositiveNumber("streamPosition")
        projectionPosition.requireZeroOrPositiveNumber("projectionPosition")
        projectionPosition.requireLe(streamPosition, "projectionPosition")
    }

    val lag: Long get() = streamPosition - projectionPosition
}

internal data class ProjectionPendingHttpResponse(
    val code: String = "PROJECTION_PENDING",
    val reason: String = "projection has not reached the requested position",
    val currentStreamPosition: Long,
    val projectionPosition: Long,
    val lag: Long,
)

internal data class CampaignProjectionRequest(
    val tenant: String,
    val principal: String,
    val campaignId: UUID,
    val minimumStreamPosition: Long?,
)

internal sealed interface CampaignProjectionResult {
    data class Fresh(
        val campaign: CampaignHttpResponse,
        val positions: ProjectionPositions,
    ) : CampaignProjectionResult

    data class Pending(
        val positions: ProjectionPositions,
    ) : CampaignProjectionResult

    data object NotFound : CampaignProjectionResult
}

internal fun interface CampaignProjectionQuery {
    fun campaign(request: CampaignProjectionRequest): CampaignProjectionResult
}

/** GET query adapter입니다. command는 이 projection-consistency path를 retry하거나 통과하지 않습니다. */
@RestController
@RequestMapping("/api/v1")
internal class EventSourcedCampaignQueryController(
    private val queries: CampaignProjectionQuery,
) {
    @GetMapping("/campaigns/{campaignId}")
    fun campaign(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(MIN_STREAM_POSITION_HEADER, required = false) minimumStreamPositionHeader: String?,
    ): ResponseEntity<Any> {
        val tenant =
            tenantHeader.requireNotNull(TENANT_HEADER)
                .requireNotBlank(TENANT_HEADER)
        val principal =
            principalHeader.requireNotNull(PRINCIPAL_HEADER)
                .requireNotBlank(PRINCIPAL_HEADER)
        val result =
            queries.campaign(
                CampaignProjectionRequest(
                    tenant = tenant,
                    principal = principal,
                    campaignId = campaignId,
                    minimumStreamPosition = minimumStreamPositionHeader.toMinimumStreamPosition(),
                ),
            )
        return result.toResponse()
    }
}

private fun CampaignProjectionResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is CampaignProjectionResult.Fresh -> ResponseEntity.ok().headers(positions.toHeaders()).body(campaign)
        is CampaignProjectionResult.Pending ->
            ResponseEntity.accepted()
                .header(HttpHeaders.RETRY_AFTER, "1")
                .headers(positions.toHeaders())
                .body(
                    ProjectionPendingHttpResponse(
                        currentStreamPosition = positions.streamPosition,
                        projectionPosition = positions.projectionPosition,
                        lag = positions.lag,
                    ),
                )

        CampaignProjectionResult.NotFound -> ResponseEntity.notFound().build()
    }

private fun ProjectionPositions.toHeaders(): HttpHeaders =
    HttpHeaders().apply {
        set(STREAM_POSITION_HEADER, streamPosition.toString())
        set(PROJECTION_POSITION_HEADER, projectionPosition.toString())
        set(PROJECTION_LAG_HEADER, lag.toString())
    }

private fun String?.toMinimumStreamPosition(): Long? {
    if (this == null) return null
    return toLongOrNull()
        .requireNotNull(MIN_STREAM_POSITION_HEADER)
        .requireZeroOrPositiveNumber(MIN_STREAM_POSITION_HEADER)
}
