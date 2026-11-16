package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireMatches
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.ReservationHoldSnapshot
import io.bluetape4k.workshop.commerce.reservation.domain.ReservationPolicies
import io.bluetape4k.workshop.commerce.reservation.domain.TransitionOutcome
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Clock
import java.time.Duration

/**
 * durable hold state transition을 소유합니다.
 *
 * PostgreSQL revision과 row lock이 모든 command outcome을 결정합니다. admission control은 이 service 앞에서
 * contention을 줄일 수 있지만, reservation을 authorize하거나 finalize하지 않습니다.
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
    companion object: KLogging() {
        private val HOLD_TTL: Duration = 30.seconds()
    }

    @Transactional
    fun hold(command: CreateHoldCommand): ReservationHoldRecord {
        val now = clock.instant()
        val resource = resources.findById(command.resourceId)
        if (resource.policyVersion != command.policyVersion) {
            throw ReservationCommandException("POLICY_VERSION_MISMATCH", resource.revision, false)
        }
        // capacity CAS와 hold insert가 이 transaction을 공유하므로, insert 실패가 occupancy를 누수하지 않습니다.
        if (!resources.tryOccupy(resource.id, command.expectedResourceRevision)) {
            throw ReservationCommandException("CAPACITY_EXHAUSTED_OR_STALE", resource.revision, true)
        }
        return holds
            .create(
                resourceId = resource.id,
                ownerDigest = credentials.ownerDigest(command.ownerToken),
                policyVersion = command.policyVersion,
                expiresAt = now.plus(HOLD_TTL)
            )
            .also {
                audits.record(
                    aggregateType = "CAPACITY_RESOURCE",
                    aggregateId = resource.id,
                    revision = command.expectedResourceRevision + 1,
                    outcome = "CAPACITY_OCCUPIED"
                )
                audits.record(
                    aggregateType = "RESERVATION_HOLD",
                    aggregateId = it.id,
                    revision = it.revision,
                    outcome = it.state.name
                )
                log.debug { "reservation_hold_command_applied holdId=${it.id} resourceId=${resource.id}" }
            }
    }

    @Transactional
    fun confirm(command: MutateHoldCommand): ReservationHoldRecord =
        mutate(command, HoldState.CONFIRMED)

    @Transactional
    fun cancel(command: MutateHoldCommand): ReservationHoldRecord =
        mutate(command, HoldState.CANCELLED)

    @Transactional
    fun extend(command: ExtendHoldCommand): ReservationHoldRecord {
        val now = clock.instant()
        val current = holds.findById(command.holdId)
        val ownerDigest = credentials.ownerDigest(command.ownerToken)
        if (current.policyVersion != command.policyVersion) {
            throw ReservationCommandException("POLICY_VERSION_MISMATCH", current.revision, false)
        }
        val newExpiresAt = maxOf(current.expiresAt, now) + command.extendBySeconds.seconds()
        val outcome = ReservationPolicies.extend(
            hold = current.toSnapshot(),
            ownerDigest = ownerDigest,
            expectedRevision = command.expectedRevision,
            now = now,
            newExpiresAt = newExpiresAt
        )

        if (outcome is TransitionOutcome.Rejected) {
            throw ReservationCommandException(outcome.reason.name, outcome.currentRevision, false)
        }
        if (!holds.extend(
                id = current.id,
                ownerDigest = ownerDigest,
                expectedRevision = current.revision,
                expectedExpiresAt = current.expiresAt,
                newExpiresAt = newExpiresAt
            )
        ) {
            throw ReservationCommandException("STALE_REVISION", current.revision, true)
        }

        return holds.findById(current.id)
            .also { updated ->
                audits.record(
                    aggregateType = "RESERVATION_HOLD",
                    aggregateId = updated.id,
                    revision = updated.revision,
                    outcome = "EXTENDED"
                )
            }
    }

    @Transactional
    fun forceRelease(command: ForceReleaseHoldCommand): ReservationHoldRecord {
        val now = clock.instant()
        val snapshot = holds.findById(command.holdId)
        // 모든 capacity handoff가 같은 global lock order를 따르도록 resource를 먼저 lock합니다.
        resources.findByIdForUpdate(snapshot.resourceId)

        val current = holds.findById(command.holdId)
        if (current.revision != command.expectedRevision) {
            throw ReservationCommandException("STALE_REVISION", current.revision, false)
        }
        if (current.state != HoldState.HELD) {
            throw ReservationCommandException("INVALID_STATE", current.revision, false)
        }
        holds.transition(
            current.id,
            current.ownerDigest,
            current.revision,
            HoldState.HELD,
            HoldState.RELEASED_BY_OPERATOR
        ).checkBeTrue { "operator hold transition lost" }

        val updated = holds.findById(current.id)
        audits.record(
            aggregateType = "RESERVATION_HOLD",
            aggregateId = updated.id,
            revision = updated.revision,
            outcome = updated.state.name,
            reason = command.reasonCode
        )
        handoff.promoteOrRelease(current.resourceId, now)
        log.debug {
            "reservation_operator_force_release holdId=${current.id} resourceId=${current.resourceId} " +
                    "reason=${command.reasonCode}"
        }
        return updated
    }

    private fun mutate(
        command: MutateHoldCommand,
        target: HoldState,
    ): ReservationHoldRecord {
        val now = clock.instant()
        val holdLocator = holds.findById(command.holdId)
        if (target == HoldState.CANCELLED) {
            // cancellation은 capacity를 waiter에게 넘길 수 있으므로 resource-first lock order에 참여합니다.
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
            else                -> error("unsupported target $target")
        }
        if (outcome is TransitionOutcome.Rejected) {
            throw ReservationCommandException(outcome.reason.name, outcome.currentRevision, false)
        }
        if (!holds.transition(current.id, ownerDigest, current.revision, HoldState.HELD, target)) {
            throw ReservationCommandException("STALE_REVISION", current.revision, true)
        }
        val updated = holds.findById(current.id)
        audits.record(
            aggregateType = "RESERVATION_HOLD",
            aggregateId = updated.id,
            revision = updated.revision,
            outcome = updated.state.name
        )
        if (target == HoldState.CANCELLED) {
            handoff.promoteOrRelease(current.resourceId, now)
        }
        return updated
    }
}

internal data class CreateHoldCommand(
    val resourceId: Long,
    val expectedResourceRevision: Long,
    val policyVersion: Long,
    val ownerToken: String,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class MutateHoldCommand(
    val holdId: Long,
    val expectedRevision: Long,
    val policyVersion: Long,
    val ownerToken: String,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class ExtendHoldCommand(
    val holdId: Long,
    val expectedRevision: Long,
    val policyVersion: Long,
    val extendBySeconds: Long,
    val ownerToken: String,
): Serializable {
    init {
        extendBySeconds.requireInRange(1, 300, "extendBySeconds")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class ForceReleaseHoldCommand(
    val holdId: Long,
    val expectedRevision: Long,
    val reasonCode: String,
): Serializable {
    init {
        reasonCode.requireMatches(Regex("[A-Z0-9_]{3,40}"), "reasonCode")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

internal class ReservationCommandException(
    val reason: String,
    val currentRevision: Long?,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
): RuntimeException(reason)

private fun ReservationHoldRecord.toSnapshot() =
    ReservationHoldSnapshot(
        id = id,
        resourceId = resourceId,
        ownerDigest = ownerDigest,
        state = state,
        revision = revision,
        policyVersion = policyVersion,
        expiresAt = expiresAt
    )
