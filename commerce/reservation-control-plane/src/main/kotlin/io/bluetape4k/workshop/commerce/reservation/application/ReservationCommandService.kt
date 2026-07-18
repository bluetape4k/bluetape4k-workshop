package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.ReservationHoldSnapshot
import io.bluetape4k.workshop.commerce.reservation.domain.ReservationPolicies
import io.bluetape4k.workshop.commerce.reservation.domain.TransitionOutcome
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.io.Serializable

/**
 * Owns durable hold state transitions.
 *
 * PostgreSQL revisions and row locks decide every command outcome. Admission controls may reduce
 * contention before this service, but they never authorize or finalize a reservation.
 */
@Service
internal class ReservationCommandService(
    private val resources: CapacityResourceRepository,
    private val holds: ReservationHoldRepository,
    private val credentials: ReservationCredentialService,
    private val clock: Clock,
    private val handoff: ReservationCapacityHandoffService,
    private val audits: ReservationAuditRepository,
) {
    @Transactional
    fun hold(command: CreateHoldCommand): ReservationHoldRecord {
        val now = clock.instant()
        val resource = resources.findById(command.resourceId)
        if (resource.policyVersion != command.policyVersion) {
            throw ReservationCommandException("POLICY_VERSION_MISMATCH", resource.revision, false)
        }
        // The capacity CAS and hold insert share this transaction, so a failed insert cannot leak occupancy.
        if (!resources.tryOccupy(resource.id, command.expectedResourceRevision)) {
            throw ReservationCommandException("CAPACITY_EXHAUSTED_OR_STALE", resource.revision, true)
        }
        return holds.create(
            resourceId = resource.id,
            ownerDigest = credentials.ownerDigest(command.ownerToken),
            policyVersion = command.policyVersion,
            expiresAt = now.plus(HOLD_TTL),
        ).also {
            audits.record("CAPACITY_RESOURCE", resource.id, command.expectedResourceRevision + 1, "CAPACITY_OCCUPIED")
            audits.record("RESERVATION_HOLD", it.id, it.revision, it.state.name)
            log.debug { "reservation_hold_command_applied holdId=${it.id} resourceId=${resource.id}" }
        }
    }

    @Transactional
    fun confirm(command: MutateHoldCommand): ReservationHoldRecord = mutate(command, HoldState.CONFIRMED)

    @Transactional
    fun cancel(command: MutateHoldCommand): ReservationHoldRecord = mutate(command, HoldState.CANCELLED)

    @Transactional
    fun extend(command: ExtendHoldCommand): ReservationHoldRecord {
        val now = clock.instant()
        val current = holds.findById(command.holdId)
        val ownerDigest = credentials.ownerDigest(command.ownerToken)
        if (current.policyVersion != command.policyVersion) {
            throw ReservationCommandException("POLICY_VERSION_MISMATCH", current.revision, false)
        }
        val newExpiresAt = maxOf(current.expiresAt, now).plusSeconds(command.extendBySeconds)
        val outcome = ReservationPolicies.extend(
            current.toSnapshot(),
            ownerDigest,
            command.expectedRevision,
            now,
            newExpiresAt,
        )
        if (outcome is TransitionOutcome.Rejected) {
            throw ReservationCommandException(outcome.reason.name, outcome.currentRevision, false)
        }
        if (!holds.extend(current.id, ownerDigest, current.revision, current.expiresAt, newExpiresAt)) {
            throw ReservationCommandException("STALE_REVISION", current.revision, true)
        }
        return holds.findById(current.id).also { updated ->
            audits.record("RESERVATION_HOLD", updated.id, updated.revision, "EXTENDED")
        }
    }

    @Transactional
    fun forceRelease(command: ForceReleaseHoldCommand): ReservationHoldRecord {
        val now = clock.instant()
        val snapshot = holds.findById(command.holdId)
        // Lock the resource first so every capacity handoff follows the same global lock order.
        resources.findByIdForUpdate(snapshot.resourceId)
        val current = holds.findById(command.holdId)
        if (current.revision != command.expectedRevision) {
            throw ReservationCommandException("STALE_REVISION", current.revision, false)
        }
        if (current.state != HoldState.HELD) {
            throw ReservationCommandException("INVALID_STATE", current.revision, false)
        }
        check(holds.transition(
            current.id,
            current.ownerDigest,
            current.revision,
            HoldState.HELD,
            HoldState.RELEASED_BY_OPERATOR,
        )) { "operator hold transition lost" }
        val updated = holds.findById(current.id)
        audits.record("RESERVATION_HOLD", updated.id, updated.revision, updated.state.name, command.reasonCode)
        handoff.promoteOrRelease(current.resourceId, now)
        log.debug {
            "reservation_operator_force_release holdId=${current.id} resourceId=${current.resourceId} " +
                "reason=${command.reasonCode}"
        }
        return updated
    }

    private fun mutate(command: MutateHoldCommand, target: HoldState): ReservationHoldRecord {
        val now = clock.instant()
        val holdLocator = holds.findById(command.holdId)
        if (target == HoldState.CANCELLED) {
            // Cancellation may hand capacity to a waiter, therefore it joins the resource-first lock order.
            resources.findByIdForUpdate(holdLocator.resourceId)
        }
        val current = holds.findById(command.holdId)
        val ownerDigest = credentials.ownerDigest(command.ownerToken)
        if (current.policyVersion != command.policyVersion) {
            throw ReservationCommandException("POLICY_VERSION_MISMATCH", current.revision, false)
        }
        val snapshot = current.toSnapshot()
        val outcome = when (target) {
            HoldState.CONFIRMED -> ReservationPolicies.confirm(snapshot, ownerDigest, command.expectedRevision, now)
            HoldState.CANCELLED -> ReservationPolicies.cancel(snapshot, ownerDigest, command.expectedRevision, now)
            else -> error("unsupported target $target")
        }
        if (outcome is TransitionOutcome.Rejected) {
            throw ReservationCommandException(outcome.reason.name, outcome.currentRevision, false)
        }
        if (!holds.transition(current.id, ownerDigest, current.revision, HoldState.HELD, target)) {
            throw ReservationCommandException("STALE_REVISION", current.revision, true)
        }
        val updated = holds.findById(current.id)
        audits.record("RESERVATION_HOLD", updated.id, updated.revision, updated.state.name)
        if (target == HoldState.CANCELLED) {
            handoff.promoteOrRelease(current.resourceId, now)
        }
        return updated
    }

    companion object : KLogging() {
        private val HOLD_TTL: Duration = Duration.ofSeconds(30)
    }
}

internal data class CreateHoldCommand(
    val resourceId: Long,
    val expectedResourceRevision: Long,
    val policyVersion: Long,
    val ownerToken: String,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

internal data class MutateHoldCommand(
    val holdId: Long,
    val expectedRevision: Long,
    val policyVersion: Long,
    val ownerToken: String,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

internal data class ExtendHoldCommand(
    val holdId: Long,
    val expectedRevision: Long,
    val policyVersion: Long,
    val extendBySeconds: Long,
    val ownerToken: String,
) : Serializable {
    init {
        require(extendBySeconds in 1..300) { "extendBySeconds must be between 1 and 300" }
    }

    companion object { private const val serialVersionUID = 1L }
}

internal data class ForceReleaseHoldCommand(
    val holdId: Long,
    val expectedRevision: Long,
    val reasonCode: String,
) : Serializable {
    init {
        require(reasonCode.matches(Regex("[A-Z0-9_]{3,40}"))) { "reasonCode must be a bounded stable code" }
    }


    companion object { private const val serialVersionUID = 1L }
}

internal class ReservationCommandException(
    val reason: String,
    val currentRevision: Long?,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(reason)

private fun ReservationHoldRecord.toSnapshot() = ReservationHoldSnapshot(
    id = id,
    resourceId = resourceId,
    ownerDigest = ownerDigest,
    state = state,
    revision = revision,
    policyVersion = policyVersion,
    expiresAt = expiresAt,
)
