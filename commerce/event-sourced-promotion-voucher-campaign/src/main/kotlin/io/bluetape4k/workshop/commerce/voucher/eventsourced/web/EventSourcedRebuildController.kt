package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedRebuildManagementService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.RebuildGenerationRequest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.RebuildManagementResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.StartRebuildRequest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

internal data class StartRebuildHttpRequest(
    @field:Min(0) val targetPosition: Long,
)

internal data class RebuildHttpResponse(
    val projection: String,
    val generation: Long,
    val state: String,
    val targetPosition: Long,
    val currentPosition: Long,
    val fencingToken: Long,
    val cancellationRevision: Long,
    val retryableFailure: Boolean,
    val replayed: Boolean,
)

@RestController
@RequestMapping("/operator/api/v1/projections/{projection}/rebuilds")
internal class EventSourcedRebuildController(
    private val service: EventSourcedRebuildManagementService,
) {
    @PostMapping
    fun start(
        @PathVariable projection: String,
        @RequestHeader headers: HttpHeaders,
        @Valid @RequestBody body: StartRebuildHttpRequest,
    ): ResponseEntity<Any> =
        service.start(
            StartRebuildRequest(
                identity = headers.operatorIdentity(),
                projection = projection,
                targetPosition = body.targetPosition,
                expectedToken = headers.expectedGenerationToken(),
            ),
        ).toResponse(HttpStatus.ACCEPTED)

    @GetMapping("/{generation}")
    fun status(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service.status(
            headers.generationRequest(projection, generation),
        ).toResponse(HttpStatus.OK)

    @PostMapping("/{generation}/cancel")
    fun cancel(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service.cancel(
            headers.generationRequest(projection, generation),
        ).toResponse(HttpStatus.OK)

    @PostMapping("/{generation}/resume")
    fun resume(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service.resume(
            headers.generationRequest(projection, generation),
        ).toResponse(HttpStatus.OK)
}

private fun HttpHeaders.generationRequest(
    projection: String,
    generation: Long,
): RebuildGenerationRequest =
    RebuildGenerationRequest(
        operatorIdentity(),
        ProjectionKey(projection, generation),
        expectedGenerationToken(),
    )

private fun RebuildManagementResult.toResponse(successStatus: HttpStatus): ResponseEntity<Any> =
    when (this) {
        is RebuildManagementResult.Accepted ->
            ResponseEntity.status(successStatus)
                .header("X-Idempotent-Replay", replayed.toString())
                .body(generation.toHttp(replayed))
        is RebuildManagementResult.StaleToken ->
            ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .header(EXPECTED_GENERATION_TOKEN_HEADER, currentToken.toString())
                .body(EventSourcedApiError("STALE_GENERATION_TOKEN", "generation token is stale"))
        is RebuildManagementResult.Conflict ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(EventSourcedApiError(reason, "rebuild conflicts with current state"))
        RebuildManagementResult.NotFound -> ResponseEntity.notFound().build()
    }

private fun ProjectionGeneration.toHttp(replayed: Boolean): RebuildHttpResponse =
    RebuildHttpResponse(
        projection = key.projection,
        generation = key.generation,
        state = state.name,
        targetPosition = targetPosition,
        currentPosition = currentPosition,
        fencingToken = fencingToken,
        cancellationRevision = cancellationRevision,
        retryableFailure = retryableFailure,
        replayed = replayed,
    )
