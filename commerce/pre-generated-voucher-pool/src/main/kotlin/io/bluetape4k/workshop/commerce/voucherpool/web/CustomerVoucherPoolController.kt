@file:Suppress("LongParameterList") // MVC route signatures keep transport preconditions explicit.

package io.bluetape4k.workshop.commerce.voucherpool.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Customer-owned reservation and allocation HTTP workflow. */
@RestController
@RequestMapping("/api/v1")
internal class CustomerVoucherPoolController(
    private val commands: VoucherPoolHttpCommandExecutor,
    private val streams: VoucherPoolEventStream,
) {
    @PostMapping("/campaigns/{campaignId}/reservations")
    fun reserve(
        principal: TenantPrincipal,
        @PathVariable campaignId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-None-Match") ifNoneMatch: String,
        @Valid @RequestBody request: ReserveVoucherRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ReservationResponse> =
        commands.reserve(principal, campaignId, idempotencyKey, ifNoneMatch, request, servletRequest.requestId())

    @GetMapping("/reservations/{reservationId}")
    fun reservation(
        principal: TenantPrincipal,
        @PathVariable reservationId: UUID,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ReservationResponse> =
        commands.reservation(principal, reservationId, servletRequest.requestId())

    @PostMapping("/reservations/{reservationId}/allocate")
    fun allocate(
        principal: TenantPrincipal,
        @PathVariable reservationId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") expectedRevision: String,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AllocationResponse> =
        commands.allocate(principal, reservationId, idempotencyKey, expectedRevision, servletRequest.requestId())

    @PostMapping("/allocations/{allocationId}/code-reveals")
    fun reveal(
        principal: TenantPrincipal,
        @PathVariable allocationId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") expectedRevision: String,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<RevealResponse> =
        commands.reveal(principal, allocationId, idempotencyKey, expectedRevision, servletRequest.requestId())

    @GetMapping("/allocations/{allocationId}")
    fun allocation(
        principal: TenantPrincipal,
        @PathVariable allocationId: UUID,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AllocationResponse> =
        commands.allocation(principal, allocationId, servletRequest.requestId())

    @PostMapping("/allocations/{allocationId}/replacements")
    fun replaceLostReveal(
        principal: TenantPrincipal,
        @PathVariable allocationId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") expectedRevision: String,
        @Valid @RequestBody request: ReplaceLostRevealRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ReservationResponse> =
        commands.replaceLostReveal(
            principal,
            allocationId,
            idempotencyKey,
            expectedRevision,
            request,
            servletRequest.requestId(),
        )

    @PostMapping("/allocations/{allocationId}/redeem")
    fun redeem(
        principal: TenantPrincipal,
        @PathVariable allocationId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") expectedRevision: String,
        @Valid @RequestBody request: RedeemVoucherRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AllocationResponse> =
        commands.redeem(principal, allocationId, idempotencyKey, expectedRevision, request, servletRequest.requestId())

    @PostMapping("/allocations/{allocationId}/release")
    fun release(
        principal: TenantPrincipal,
        @PathVariable allocationId: UUID,
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestHeader("If-Match") expectedRevision: String,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AllocationResponse> =
        commands.release(principal, allocationId, idempotencyKey, expectedRevision, servletRequest.requestId())

    @GetMapping("/snapshots")
    fun snapshots(
        principal: TenantPrincipal,
        servletRequest: HttpServletRequest,
    ): VoucherPoolSnapshotResponse =
        streams.customerSnapshot(principal.tenantId, principal.principalId, servletRequest.requestId())

    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        principal: TenantPrincipal,
        @RequestParam(required = false) cursor: String?,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
        response: HttpServletResponse,
    ) {
        val subscription = streams.openCustomer(
            principal.tenantId,
            principal.principalId,
            resolveEventCursor(cursor, lastEventId),
        )
        response.contentType = MediaType.TEXT_EVENT_STREAM_VALUE
        streams.write(subscription, response.outputStream)
    }
}

/** Closed JSON object for reservation creation. */
internal class ReserveVoucherRequest : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Explicit acknowledgement that a one-time reveal response was lost. */
internal data class ReplaceLostRevealRequest(
    @field:AssertTrue
    val confirmLostReveal: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Voucher code supplied only to the verification-backed redemption command. */
internal data class RedeemVoucherRequest(
    @field:NotBlank
    @field:Size(max = 512)
    val code: String,
) : Serializable {
    override fun toString(): String = "RedeemVoucherRequest(code=[REDACTED])"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Code-free customer reservation representation. */
internal data class ReservationResponse(
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val state: String,
    val expiresAt: Instant,
    val entitlementRootId: UUID?,
    val replacementOrdinal: Int,
    val policyVersion: Long,
    val revision: Long,
    val observedAt: Instant,
    val requestId: String,
    val nextAction: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Code-free customer allocation representation. */
internal data class AllocationResponse(
    val allocationId: UUID,
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val state: String,
    val expiresAt: Instant,
    val entitlementRootId: UUID,
    val replacementOrdinal: Int,
    val policyVersion: Long,
    val revision: Long,
    val observedAt: Instant,
    val requestId: String,
    val nextAction: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** One-time reveal representation; [code] is absent after the first committed delivery. */
internal data class RevealResponse(
    val allocationId: UUID,
    val outcome: String,
    val revision: Long,
    val codeAvailable: Boolean,
    val code: String?,
    val observedAt: Instant,
    val requestId: String,
    val nextAction: String,
) : Serializable {
    override fun toString(): String =
        "RevealResponse(allocationId=$allocationId,outcome=$outcome,revision=$revision," +
            "codeAvailable=$codeAvailable,code=[REDACTED],observedAt=$observedAt," +
            "requestId=$requestId,nextAction=$nextAction)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
