package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findGeneration
import java.io.Serializable
import java.time.Instant

/** mutation과 immutable evidence가 transaction 하나를 공유하는 fenced operator request입니다. */
@ConsistentCopyVisibility
internal data class RebuildCancellationCommand private constructor(
    val identity: OperatorAuditIdentity,
    val target: OperatorAuditTarget,
    val checkpointPosition: Long,
    val streamPosition: Long,
    val occurredAt: Instant,
) : Serializable {
    val key: ProjectionKey get() = ProjectionKey(target.projection, target.generation)

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            identity: OperatorAuditIdentity,
            target: OperatorAuditTarget,
            checkpointPosition: Long,
            streamPosition: Long,
            occurredAt: Instant,
        ): RebuildCancellationCommand {
            val validCheckpoint = checkpointPosition.requireZeroOrPositiveNumber("checkpointPosition")
            val validStreamPosition = streamPosition.requireZeroOrPositiveNumber("streamPosition")
            return RebuildCancellationCommand(
                identity = identity,
                target = target,
                checkpointPosition = validCheckpoint,
                streamPosition = validStreamPosition,
                occurredAt = occurredAt,
            )
        }
    }
}

internal data class RebuildCancellationOutcome(
    val state: ProjectionGenerationState,
    val audit: OperatorAuditEntry,
)

/**
 * rebuild cancellation, fencing verification, operator evidence를 분리할 수 없게 유지합니다.
 * generic mutation API를 의도적으로 노출하지 않습니다. caller는 같은 foreground database transaction에서
 * 일치하는 audit record를 쓰지 않으면 rebuild state를 advance할 수 없습니다.
 */
internal class EventSourcedRebuildOperator(
    private val transactions: EventSourcedPermitTransactionRunner,
    private val rebuilds: ProjectionRebuildRepository,
    private val audits: OperatorAuditRepository,
) {
    fun cancel(command: RebuildCancellationCommand): RebuildCancellationOutcome =
        transactions.inTransaction {
            val before = checkNotNull(findGeneration(command.key)) { "rebuild generation does not exist" }
            val after = cancellationResult(command, before.state, before.fencingToken)
            val outcome =
                if (after.fencingToken != before.fencingToken) OperatorAuditOutcome.APPLIED
                else OperatorAuditOutcome.REJECTED
            val audit =
                OperatorAuditEntry(
                    identity = command.identity,
                    action = OperatorAuditAction.REBUILD_CANCELLED,
                    target = command.target,
                    transition =
                        OperatorAuditTransition(
                            beforeState = before.state,
                            afterState = after.state,
                            checkpointPosition = command.checkpointPosition,
                            streamPosition = command.streamPosition,
                            reasonClass = if (outcome == OperatorAuditOutcome.APPLIED) null else REJECTED_REASON,
                        ),
                    result = OperatorAuditResult(outcome, command.occurredAt),
                )
            audits.append(audit)
            RebuildCancellationOutcome(after.state, audit)
        }

    private fun cancellationResult(
        command: RebuildCancellationCommand,
        beforeState: ProjectionGenerationState,
        observedFencingToken: Long,
    ) =
        if (command.target.expectedFencingToken != observedFencingToken || beforeState !in CANCELLABLE_STATES) {
            checkNotNull(findGeneration(command.key)) { "rebuild generation disappeared" }
        } else {
            checkNotNull(rebuilds.requestCancellation(command.key, command.occurredAt)) {
                "rebuild generation disappeared during cancellation"
            }
        }

    private companion object {
        private const val REJECTED_REASON = "REBUILD_NOT_CANCELLABLE"
        private val CANCELLABLE_STATES = setOf(ProjectionGenerationState.BUILDING, ProjectionGenerationState.VALIDATING)
    }
}
