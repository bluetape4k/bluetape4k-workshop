package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.application.CreateHoldCommand
import io.bluetape4k.workshop.commerce.reservation.application.ExtendHoldCommand
import io.bluetape4k.workshop.commerce.reservation.application.IdempotentCommandResult
import io.bluetape4k.workshop.commerce.reservation.application.IdempotentReservationCommandService
import io.bluetape4k.workshop.commerce.reservation.application.MutateHoldCommand
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandExecutionGate
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandService
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRecord
import io.bluetape4k.workshop.commerce.reservation.query.ReservationQueryService
import io.bluetape4k.workshop.commerce.reservation.query.ResourceSnapshotResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable
import java.time.Instant

/** hold command용 HTTP boundary입니다. 모든 mutation은 admission, suppression, durable idempotency를 거칩니다. */
@RestController
@RequestMapping("/api")
internal class ReservationController(
    private val commands: ReservationCommandService,
    private val queries: ReservationQueryService,
    private val executionGate: ReservationCommandExecutionGate,
    private val idempotency: IdempotentReservationCommandService,
) {
    @GetMapping("/resources")
    fun resources(): ResourceSnapshotResponse = queries.resources()

    @PostMapping("/resources/{resourceId}/holds")
    fun hold(
        @PathVariable resourceId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreateHoldRequest,
    ): ResponseEntity<HoldResponse> {
        validateHeaders(owner, idempotencyKey)
        val result =
            executionGate.execute(CREATE_HOLD, idempotencyKey) {
                idempotency.execute(
                    operation = CREATE_HOLD,
                    rawKey = idempotencyKey,
                    rawOwner = owner,
                    canonicalPayload =
                        "resourceId=$resourceId\nexpectedRevision=${request.expectedResourceRevision}\n" +
                            "policyVersion=${request.policyVersion}",
                    successStatus = HttpStatus.CREATED.value(),
                    bodyType = HoldResponse::class.java
                ) {
                    commands
                        .hold(
                            CreateHoldCommand(
                                resourceId,
                                request.expectedResourceRevision,
                                request.policyVersion,
                                owner
                            )
                        ).toResponse()
                }
            }
        log.debug { "reservation_http_hold_applied holdId=${result.value.id} resourceId=$resourceId" }
        return result.toResponseEntity()
    }

    @PostMapping("/holds/{holdId}/confirm")
    fun confirm(
        @PathVariable holdId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: MutateHoldRequest,
    ): ResponseEntity<HoldResponse> = mutate(CONFIRM_HOLD, holdId, owner, idempotencyKey, request, commands::confirm)

    @PostMapping("/holds/{holdId}/cancel")
    fun cancel(
        @PathVariable holdId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: MutateHoldRequest,
    ): ResponseEntity<HoldResponse> = mutate(CANCEL_HOLD, holdId, owner, idempotencyKey, request, commands::cancel)

    @PostMapping("/holds/{holdId}/extend")
    fun extend(
        @PathVariable holdId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: ExtendHoldRequest,
    ): ResponseEntity<HoldResponse> {
        validateHeaders(owner, idempotencyKey)
        val result =
            executionGate.execute(EXTEND_HOLD, idempotencyKey) {
                idempotency.execute(
                    operation = EXTEND_HOLD,
                    rawKey = idempotencyKey,
                    rawOwner = owner,
                    canonicalPayload =
                        "holdId=$holdId\nexpectedRevision=${request.expectedRevision}\n" +
                            "policyVersion=${request.policyVersion}\nextendBySeconds=${request.extendBySeconds}",
                    successStatus = HttpStatus.OK.value(),
                    bodyType = HoldResponse::class.java
                ) {
                    commands
                        .extend(
                            ExtendHoldCommand(
                                holdId = holdId,
                                expectedRevision = request.expectedRevision,
                                policyVersion = request.policyVersion,
                                extendBySeconds = request.extendBySeconds,
                                ownerToken = owner
                            )
                        ).toResponse()
                }
            }
        return result.toResponseEntity()
    }

    private fun mutate(
        operation: String,
        holdId: Long,
        owner: String,
        idempotencyKey: String,
        request: MutateHoldRequest,
        action: (MutateHoldCommand) -> ReservationHoldRecord,
    ): ResponseEntity<HoldResponse> {
        validateHeaders(owner, idempotencyKey)
        val result =
            executionGate.execute(operation, idempotencyKey) {
                idempotency.execute(
                    operation = operation,
                    rawKey = idempotencyKey,
                    rawOwner = owner,
                    canonicalPayload =
                        "holdId=$holdId\nexpectedRevision=${request.expectedRevision}\n" +
                            "policyVersion=${request.policyVersion}",
                    successStatus = HttpStatus.OK.value(),
                    bodyType = HoldResponse::class.java
                ) {
                    action(
                        MutateHoldCommand(holdId, request.expectedRevision, request.policyVersion, owner)
                    ).toResponse()
                }
            }
        return result.toResponseEntity()
    }

    private fun validateHeaders(
        owner: String,
        idempotencyKey: String,
    ) {
        require(owner.length >= 32) { "owner credential must contain at least 256 bits of encoded entropy" }
        require(idempotencyKey.length >= 16) { "idempotency key must contain at least 128 bits of encoded entropy" }
    }

    companion object : KLogging() {
        private const val CREATE_HOLD = "CREATE_HOLD"
        private const val CONFIRM_HOLD = "CONFIRM_HOLD"
        private const val CANCEL_HOLD = "CANCEL_HOLD"
        private const val EXTEND_HOLD = "EXTEND_HOLD"
    }
}

internal data class CreateHoldRequest(
    @field:Min(0) val expectedResourceRevision: Long,
    @field:Min(1) val policyVersion: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class MutateHoldRequest(
    @field:Min(0) val expectedRevision: Long,
    @field:Min(1) val policyVersion: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class ExtendHoldRequest(
    @field:Min(0) val expectedRevision: Long,
    @field:Min(1) val policyVersion: Long,
    @field:Min(1) val extendBySeconds: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class HoldResponse(
    val id: Long,
    val resourceId: Long,
    val state: String,
    val revision: Long,
    val policyVersion: Long,
    val expiresAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private fun ReservationHoldRecord.toResponse() =
    HoldResponse(id, resourceId, state.name, revision, policyVersion, expiresAt)

private fun <T : Any> IdempotentCommandResult<T>.toResponseEntity(): ResponseEntity<T> =
    ResponseEntity
        .status(status)
        .header("Idempotency-Replayed", replayed.toString())
        .body(value)
