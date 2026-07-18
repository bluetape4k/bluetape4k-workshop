package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.reservation.application.ForceReleaseHoldCommand
import io.bluetape4k.workshop.commerce.reservation.application.IdempotentReservationCommandService
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandExecutionGate
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandService
import io.bluetape4k.workshop.commerce.reservation.sweeper.PostgresReservationSweepWork
import io.bluetape4k.workshop.commerce.reservation.sweeper.SweepBatchSummary
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable
import java.time.Duration

/**
 * Authenticated operator boundary for force release and bounded manual sweep commands.
 * Force release remains revision-checked, audited, and idempotent like a user command.
 */
@RestController
@RequestMapping("/api/operator")
@ConditionalOnProperty(prefix = "reservation.operator", name = ["enabled"], havingValue = "true")
internal class OperatorController(
    private val commands: ReservationCommandService,
    private val sweepWork: PostgresReservationSweepWork,
    private val executionGate: ReservationCommandExecutionGate,
    private val idempotency: IdempotentReservationCommandService,
) {
    @PostMapping("/holds/{holdId}/force-release")
    fun forceRelease(
        @PathVariable holdId: Long,
        @RequestHeader("X-Operator-Key") operatorKey: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: ForceReleaseRequest,
    ): ResponseEntity<OperatorReleaseResponse> {
        val result = executionGate.execute(FORCE_RELEASE, key) {
            idempotency.execute(
                FORCE_RELEASE,
                key,
                operatorKey,
                "holdId=$holdId\nexpectedRevision=${request.expectedRevision}\nreasonCode=${request.reasonCode}",
                HttpStatus.OK.value(),
                OperatorReleaseResponse::class.java,
            ) {
                commands.forceRelease(ForceReleaseHoldCommand(holdId, request.expectedRevision, request.reasonCode))
                    .let { OperatorReleaseResponse(it.id, it.resourceId, it.state.name, it.revision) }
            }
        }
        log.info { "reservation_operator_force_release_completed holdId=$holdId replayed=${result.replayed}" }
        return ResponseEntity.status(result.status)
            .header("Idempotency-Replayed", result.replayed.toString())
            .body(result.value)
    }

    @PostMapping("/sweep")
    fun sweep(@Valid @RequestBody request: ManualSweepRequest): SweepBatchSummary =
        sweepWork.sweep(request.maxResources, Duration.ofSeconds(5)).also { summary ->
            log.info {
                "reservation_operator_sweep_completed scanned=${summary.scannedResources} " +
                    "expired=${summary.expiredHolds} promoted=${summary.promotedEntries}"
            }
        }

    companion object : KLogging() {
        private const val FORCE_RELEASE = "OPERATOR_FORCE_RELEASE"
    }
}

internal data class ForceReleaseRequest(
    @field:Min(0) val expectedRevision: Long,
    @field:Pattern(regexp = "[A-Z0-9_]{3,40}") val reasonCode: String,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

internal data class ManualSweepRequest(
    @field:Min(1) @field:Max(32) val maxResources: Int = 32,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

internal data class OperatorReleaseResponse(
    val holdId: Long,
    val resourceId: Long,
    val state: String,
    val revision: Long,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}
