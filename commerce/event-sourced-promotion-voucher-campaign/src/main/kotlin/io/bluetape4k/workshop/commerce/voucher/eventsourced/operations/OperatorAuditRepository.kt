package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.OperatorAudits
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
            val validGeneration = generation.requirePositiveNumber("generation")
            val validToken = expectedFencingToken.requirePositiveNumber("expectedFencingToken")
            return OperatorAuditTarget(
                projection.requireNotBlank("projection"),
                validGeneration,
                validToken,
            )
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
            val validCheckpoint = checkpointPosition.requireZeroOrPositiveNumber("checkpointPosition")
            val validStreamPosition = streamPosition.requireZeroOrPositiveNumber("streamPosition")
            (reasonClass == null || REASON_CLASS.matches(reasonClass))
                .requireEquals(true, "reasonClass.boundedStableCode")
            return OperatorAuditTransition(beforeState, afterState, validCheckpoint, validStreamPosition, reasonClass)
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
    fun find(
        tenant: String,
        requestDigest: ReceiptDigest,
        action: OperatorAuditAction,
    ): OperatorAuditEntry? {
        TransactionManager.current()
        return OperatorAudits
            .selectAll()
            .where {
                (OperatorAudits.tenant eq tenant) and
                    (OperatorAudits.requestDigest eq requestDigest.value) and
                    (OperatorAudits.action eq action)
            }.singleOrNull()
            ?.let(::toAuditEntry)
    }

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

private fun toAuditEntry(row: ResultRow): OperatorAuditEntry =
    OperatorAuditEntry(
        identity =
            OperatorAuditIdentity(
                actorDigest = ReceiptDigest.of(row[OperatorAudits.actorDigest]),
                tenant = row[OperatorAudits.tenant],
                requestDigest = ReceiptDigest.of(row[OperatorAudits.requestDigest]),
            ),
        action = row[OperatorAudits.action],
        target =
            OperatorAuditTarget(
                projection = row[OperatorAudits.projection],
                generation = row[OperatorAudits.generation],
                expectedFencingToken = row[OperatorAudits.expectedFencingToken],
            ),
        transition =
            OperatorAuditTransition(
                beforeState = row[OperatorAudits.beforeState],
                afterState = row[OperatorAudits.afterState],
                checkpointPosition = row[OperatorAudits.checkpointPosition],
                streamPosition = row[OperatorAudits.streamPosition],
                reasonClass = row[OperatorAudits.reasonClass],
            ),
        result =
            OperatorAuditResult(
                outcome = row[OperatorAudits.outcome],
                occurredAt = row[OperatorAudits.occurredAt],
            ),
    )
