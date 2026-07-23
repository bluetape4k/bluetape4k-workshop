package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.PoisonRetryRequest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.PoisonRetryResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.ProjectionReconciliationRequest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.ProjectionReconciliationResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.ProjectionRecoveryManagementService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

internal data class PoisonRetryHttpResponse(
    val eventId: UUID,
    val state: String,
    val attempts: Int,
    val checkpointPosition: Long,
    val replayed: Boolean,
)

internal data class ProjectionReconciliationHttpResponse(
    val streamPosition: Long,
    val checkpointPosition: Long,
    val lag: Long,
    val failedPoisonCount: Long,
    val replayed: Boolean,
)

@RestController
@RequestMapping("/operator/api/v1/projections/{projection}/generations/{generation}")
internal class EventSourcedProjectionRecoveryController(
    private val service: ProjectionRecoveryManagementService,
) {
    @PostMapping("/poison-events/{eventId}/retry")
    fun retryPoison(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @PathVariable eventId: UUID,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service.retryPoison(
            PoisonRetryRequest(
                identity = headers.operatorIdentity(),
                key = ProjectionKey(projection, generation),
                eventId = eventId,
                expectedToken = headers.expectedGenerationToken(),
            ),
        ).toResponse()

    @PostMapping("/reconciliation")
    fun reconcile(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service.reconcile(
            ProjectionReconciliationRequest(
                identity = headers.operatorIdentity(),
                key = ProjectionKey(projection, generation),
                expectedToken = headers.expectedGenerationToken(),
            ),
        ).toResponse()
}

private fun PoisonRetryResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is PoisonRetryResult.Resolved ->
            ResponseEntity.ok()
                .header("X-Idempotent-Replay", replayed.toString())
                .body(
                    PoisonRetryHttpResponse(
                        eventId = poison.eventId,
                        state = poison.state.name,
                        attempts = poison.attempts,
                        checkpointPosition = checkpointPosition,
                        replayed = replayed,
                    ),
                )
        is PoisonRetryResult.RetryLater ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, delay.retryAfterSeconds().toString())
                .body(EventSourcedApiError("POISON_RETRY_BACKOFF", "poison retry is not ready"))
        is PoisonRetryResult.StaleToken ->
            ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .header(EXPECTED_GENERATION_TOKEN_HEADER, currentToken.toString())
                .body(EventSourcedApiError("STALE_GENERATION_TOKEN", "generation token is stale"))
        is PoisonRetryResult.Conflict ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(EventSourcedApiError(reason, "poison retry conflicts with current state"))
        PoisonRetryResult.NotFound -> ResponseEntity.notFound().build()
    }

private fun ProjectionReconciliationResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is ProjectionReconciliationResult.Completed ->
            ResponseEntity.ok()
                .header("X-Idempotent-Replay", replayed.toString())
                .body(
                    ProjectionReconciliationHttpResponse(
                        streamPosition = snapshot.streamPosition,
                        checkpointPosition = snapshot.checkpointPosition,
                        lag = snapshot.lag,
                        failedPoisonCount = snapshot.failedPoisonCount,
                        replayed = replayed,
                    ),
                )
        is ProjectionReconciliationResult.StaleToken ->
            ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .header(EXPECTED_GENERATION_TOKEN_HEADER, currentToken.toString())
                .body(EventSourcedApiError("STALE_GENERATION_TOKEN", "generation token is stale"))
        ProjectionReconciliationResult.NotFound -> ResponseEntity.notFound().build()
    }

private fun Duration.retryAfterSeconds(): Long =
    ((toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(1)

private const val MILLIS_PER_SECOND = 1_000L
