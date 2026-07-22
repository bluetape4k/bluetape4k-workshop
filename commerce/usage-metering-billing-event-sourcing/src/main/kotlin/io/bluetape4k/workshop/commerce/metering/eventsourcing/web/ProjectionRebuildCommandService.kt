package io.bluetape4k.workshop.commerce.metering.eventsourcing.web

import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandAcquireResult
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandReceiptService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionRebuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Component
class ProjectionRebuildCommandService(
    private val rebuilder: ProjectionRebuilder,
    private val receipts: CommandReceiptService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    fun begin(projectionName: String, key: String, actorId: String): ResponseEntity<Any> {
        val scope = CommandScope(OPERATOR_SCOPE, "projection-rebuild:$projectionName", CommandFingerprint.key(key))
        val fingerprint = CommandFingerprint.request("projection-rebuild", mapOf("projection" to projectionName))
        return when (val acquired = receipts.acquire(scope, fingerprint, clock.instant())) {
            is CommandAcquireResult.Owned -> beginOwned(projectionName, actorId, acquired)
            is CommandAcquireResult.Replay -> ResponseEntity.status(acquired.httpStatus)
                .header("Idempotency-Replayed", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(acquired.response)
            is CommandAcquireResult.InProgress -> ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", acquired.retryAfter.seconds.coerceAtLeast(1).toString())
                .body(ApiError("command_in_progress", "command_in_progress"))
            CommandAcquireResult.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError("idempotency_conflict", "idempotency_conflict"))
        }
    }

    private fun beginOwned(
        projectionName: String,
        actorId: String,
        owned: CommandAcquireResult.Owned,
    ): ResponseEntity<Any> {
        receipts.requireOwnership(owned, clock.instant())
        val generation = rebuilder.beginNext(projectionName, clock.instant())
        val response = ProjectionStatusResponse(
            projectionName,
            generation.generation,
            generation.state.name,
            generation.checkpoint,
            generation.highWatermark,
            (generation.highWatermark - generation.checkpoint).coerceAtLeast(0),
            false,
            null,
            null,
            actorId,
        )
        val completed = receipts.succeed(
            owned,
            HttpStatus.ACCEPTED.value(),
            mapper.writeValueAsString(response),
            clock.instant(),
        )
        check(completed) { "command_receipt_owner_lost" }
        return ResponseEntity.accepted().body(response)
    }

    private companion object {
        const val OPERATOR_SCOPE = "operator-control"
    }
}
