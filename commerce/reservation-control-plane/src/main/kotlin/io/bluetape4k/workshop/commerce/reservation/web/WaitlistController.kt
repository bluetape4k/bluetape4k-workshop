package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.application.AcceptOfferCommand
import io.bluetape4k.workshop.commerce.reservation.application.CancelWaitlistCommand
import io.bluetape4k.workshop.commerce.reservation.application.IdempotentCommandResult
import io.bluetape4k.workshop.commerce.reservation.application.IdempotentReservationCommandService
import io.bluetape4k.workshop.commerce.reservation.application.JoinWaitlistCommand
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandExecutionGate
import io.bluetape4k.workshop.commerce.reservation.application.WaitlistCommandService
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRecord
import io.bluetape4k.workshop.commerce.reservation.query.WaitlistQueryService
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

/** replay 가능한 idempotent response를 가진 owner-scoped waitlist 및 offer command용 HTTP boundary입니다. */
@RestController
@RequestMapping("/api")
internal class WaitlistController(
    private val commands: WaitlistCommandService,
    private val queries: WaitlistQueryService,
    private val executionGate: ReservationCommandExecutionGate,
    private val idempotency: IdempotentReservationCommandService,
) {
    @PostMapping("/resources/{resourceId}/waitlist")
    fun join(
        @PathVariable resourceId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: JoinWaitlistRequest,
    ): ResponseEntity<WaitlistResponse> {
        validateCommandHeaders(owner, key)
        val result =
            executionGate.execute(JOIN_WAITLIST, key) {
                idempotency.execute(
                    JOIN_WAITLIST,
                    key,
                    owner,
                    "resourceId=$resourceId\nexpectedRevision=${request.expectedResourceRevision}\n" +
                        "policyVersion=${request.policyVersion}",
                    HttpStatus.CREATED.value(),
                    WaitlistResponse::class.java
                ) {
                    commands
                        .join(
                            JoinWaitlistCommand(
                                resourceId,
                                owner,
                                request.expectedResourceRevision,
                                request.policyVersion
                            )
                        ).toResponse(position = 0, offer = null)
                }
            }
        log.debug { "waitlist_http_join_applied entryId=${result.value.id} resourceId=$resourceId" }
        return result.toResponseEntity()
    }

    @GetMapping("/waitlist/{entryId}")
    fun entry(
        @PathVariable entryId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
    ): WaitlistResponse {
        require(owner.length >= 32) { "owner credential must contain at least 256 bits of encoded entropy" }
        val snapshot = queries.entry(entryId, owner)
        return snapshot.entry.toResponse(snapshot.position, snapshot.activeOffer)
    }

    @PostMapping("/waitlist/{entryId}/cancel")
    fun cancel(
        @PathVariable entryId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: RevisionRequest,
    ): ResponseEntity<WaitlistResponse> {
        validateCommandHeaders(owner, key)
        val result =
            executionGate.execute(CANCEL_WAITLIST, key) {
                idempotency.execute(
                    CANCEL_WAITLIST,
                    key,
                    owner,
                    "entryId=$entryId\nexpectedRevision=${request.expectedRevision}",
                    HttpStatus.OK.value(),
                    WaitlistResponse::class.java
                ) {
                    commands.cancel(CancelWaitlistCommand(entryId, request.expectedRevision, owner)).toResponse(0, null)
                }
            }
        return result.toResponseEntity()
    }

    @GetMapping("/offers/{offerId}")
    fun offer(
        @PathVariable offerId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
    ): OfferResponse {
        require(owner.length >= 32) { "owner credential must contain at least 256 bits of encoded entropy" }
        return queries.offer(offerId, owner).toResponse()
    }

    @PostMapping("/offers/{offerId}/accept")
    fun accept(
        @PathVariable offerId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: RevisionRequest,
    ): ResponseEntity<OfferResponse> {
        validateCommandHeaders(owner, key)
        val result =
            executionGate.execute(ACCEPT_OFFER, key) {
                idempotency.execute(
                    ACCEPT_OFFER,
                    key,
                    owner,
                    "offerId=$offerId\nexpectedRevision=${request.expectedRevision}",
                    HttpStatus.OK.value(),
                    OfferResponse::class.java
                ) {
                    commands.accept(AcceptOfferCommand(offerId, request.expectedRevision, owner)).offer.toResponse()
                }
            }
        return result.toResponseEntity()
    }

    private fun validateCommandHeaders(
        owner: String,
        key: String,
    ) {
        require(owner.length >= 32) { "owner credential must contain at least 256 bits of encoded entropy" }
        require(key.length in 16..200) { "idempotency key must contain 16..200 characters" }
    }

    companion object : KLogging() {
        private const val JOIN_WAITLIST = "JOIN_WAITLIST"
        private const val CANCEL_WAITLIST = "CANCEL_WAITLIST"
        private const val ACCEPT_OFFER = "ACCEPT_OFFER"
    }
}

internal data class JoinWaitlistRequest(
    @field:Min(0) val expectedResourceRevision: Long,
    @field:Min(1) val policyVersion: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class RevisionRequest(
    @field:Min(0) val expectedRevision: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class WaitlistResponse(
    val id: Long,
    val resourceId: Long,
    val state: String,
    val sequence: Long,
    val revision: Long,
    val position: Int,
    val offerId: Long? = null,
    val offerRevision: Long? = null,
    val offerExpiresAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class OfferResponse(
    val id: Long,
    val resourceId: Long,
    val entryId: Long,
    val state: String,
    val revision: Long,
    val expiresAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private fun WaitlistEntryRecord.toResponse(
    position: Int,
    offer: ReservationOfferRecord?,
) = WaitlistResponse(
    id = id,
    resourceId = resourceId,
    state = state.name,
    sequence = sequence,
    revision = revision,
    position = position,
    offerId = offer?.id,
    offerRevision = offer?.revision,
    offerExpiresAt = offer?.expiresAt
)

private fun ReservationOfferRecord.toResponse() =
    OfferResponse(id, resourceId, entryId, state.name, revision, expiresAt)

private fun <T : Any> IdempotentCommandResult<T>.toResponseEntity(): ResponseEntity<T> =
    ResponseEntity.status(status).header("Idempotency-Replayed", replayed.toString()).body(value)
