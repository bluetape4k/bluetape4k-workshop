package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.OperatorAudits
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.io.Serializable
import java.time.Instant

internal enum class OperatorAuditAction {
    REBUILD_STARTED,
    REBUILD_CANCELLED,
    REBUILD_RESUMED,
    REBUILD_ACTIVATED,
    POISON_RETRIED,
    RECONCILIATION_RUN,
}

internal enum class OperatorAuditOutcome {
    APPLIED,
    REJECTED,
}

/** Immutable operator mutation evidence. It stores only digests for actor and request identity. */
@ConsistentCopyVisibility
internal data class OperatorAuditIdentity private constructor(
    val actorDigest: ReceiptDigest,
    val tenant: String,
    val requestDigest: ReceiptDigest,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            actorDigest: ReceiptDigest,
            tenant: String,
            requestDigest: ReceiptDigest,
        ): OperatorAuditIdentity = OperatorAuditIdentity(actorDigest, tenant.requireNotBlank("tenant"), requestDigest)
    }
}

@ConsistentCopyVisibility
internal data class OperatorAuditTarget private constructor(
    val projection: String,
    val generation: Long,
    val expectedFencingToken: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            projection: String,
            generation: Long,
            expectedFencingToken: Long,
        ): OperatorAuditTarget {
            require(generation > 0) { "generation must be positive" }
            require(expectedFencingToken > 0) { "expectedFencingToken must be positive" }
            return OperatorAuditTarget(projection.requireNotBlank("projection"), generation, expectedFencingToken)
        }
    }
}

@ConsistentCopyVisibility
internal data class OperatorAuditTransition private constructor(
    val beforeState: ProjectionGenerationState?,
    val afterState: ProjectionGenerationState?,
    val checkpointPosition: Long,
    val streamPosition: Long,
    val reasonClass: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
        private val REASON_CLASS = Regex("[A-Z][A-Z0-9_]{0,63}")

        operator fun invoke(
            beforeState: ProjectionGenerationState?,
            afterState: ProjectionGenerationState?,
            checkpointPosition: Long,
            streamPosition: Long,
            reasonClass: String?,
        ): OperatorAuditTransition {
            require(checkpointPosition >= 0) { "checkpointPosition must be non-negative" }
            require(streamPosition >= 0) { "streamPosition must be non-negative" }
            require(reasonClass == null || REASON_CLASS.matches(reasonClass)) {
                "reasonClass must be a bounded stable code"
            }
            return OperatorAuditTransition(beforeState, afterState, checkpointPosition, streamPosition, reasonClass)
        }
    }
}

internal data class OperatorAuditResult(
    val outcome: OperatorAuditOutcome,
    val occurredAt: Instant,
)

@ConsistentCopyVisibility
internal data class OperatorAuditEntry private constructor(
    val identity: OperatorAuditIdentity,
    val action: OperatorAuditAction,
    val target: OperatorAuditTarget,
    val transition: OperatorAuditTransition,
    val result: OperatorAuditResult,
) : Serializable {
    val actorDigest: ReceiptDigest get() = identity.actorDigest
    val tenant: String get() = identity.tenant
    val requestDigest: ReceiptDigest get() = identity.requestDigest
    val projection: String get() = target.projection
    val generation: Long get() = target.generation
    val expectedFencingToken: Long get() = target.expectedFencingToken
    val beforeState: ProjectionGenerationState? get() = transition.beforeState
    val afterState: ProjectionGenerationState? get() = transition.afterState
    val checkpointPosition: Long get() = transition.checkpointPosition
    val streamPosition: Long get() = transition.streamPosition
    val reasonClass: String? get() = transition.reasonClass
    val outcome: OperatorAuditOutcome get() = result.outcome
    val occurredAt: Instant get() = result.occurredAt

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            identity: OperatorAuditIdentity,
            action: OperatorAuditAction,
            target: OperatorAuditTarget,
            transition: OperatorAuditTransition,
            result: OperatorAuditResult,
        ): OperatorAuditEntry = OperatorAuditEntry(identity, action, target, transition, result)
    }
}

/**
 * Transaction-bound append-only audit boundary. It deliberately has no generic CRUD API because
 * callers must persist audit evidence in the same fenced mutation transaction they describe.
 */
internal class OperatorAuditRepository {
    fun append(entry: OperatorAuditEntry): OperatorAuditEntry {
        TransactionManager.current()
        OperatorAudits.insert { row ->
            row[actorDigest] = entry.actorDigest.value
            row[tenant] = entry.tenant
            row[requestDigest] = entry.requestDigest.value
            row[action] = entry.action
            row[projection] = entry.projection
            row[generation] = entry.generation
            row[expectedFencingToken] = entry.expectedFencingToken
            row[beforeState] = entry.beforeState
            row[afterState] = entry.afterState
            row[checkpointPosition] = entry.checkpointPosition
            row[streamPosition] = entry.streamPosition
            row[outcome] = entry.outcome
            row[reasonClass] = entry.reasonClass
            row[occurredAt] = entry.occurredAt
        }
        return entry
    }
}
