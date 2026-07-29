package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.EventSourcedIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptAcquireResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalReplay
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptScope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalDescriptor
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import java.time.Duration
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/** 두 transaction 경계를 소유합니다. 먼저 committed receipt를 acquire하고, 이후 atomic append와 terminal finalize를 수행합니다. */
internal interface CommandTransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}

internal class ExposedCommandTransactionRunner(
    private val database: Database,
    permits: EventSourcedDatabasePermitGate,
) : CommandTransactionRunner {
    private val transactions =
        EventSourcedPermitTransactionRunner(database, permits, EventSourcedDatabaseLane.FOREGROUND)

    override fun <T> inTransaction(block: () -> T): T = transactions.inTransaction(block)
}

internal class EventSourcedCommand(
    val scope: ReceiptScope,
    val fingerprint: ReceiptDigest,
    val acquiredAt: Instant,
    val decideAfterRehydrate: () -> EventSourcedCommandDecision,
)

internal class EventSourcedCommandDecision(
    val appends: List<ExpectedAppend>,
    val descriptor: TerminalDescriptor,
)

internal sealed interface CommandExecutionResult {
    class Executed(
        val append: AppendResult.Appended?,
        val descriptor: TerminalDescriptor,
    ) : CommandExecutionResult

    class Replayed(val descriptor: TerminalDescriptor) : CommandExecutionResult

    class InProgress : CommandExecutionResult

    class FingerprintConflict : CommandExecutionResult

    class KeyUnavailable : CommandExecutionResult
}

internal class EventSourcedCommandService(
    private val transactions: CommandTransactionRunner,
    private val receipts: EventSourcedIdempotencyRepository,
    private val eventStore: EventStorePort,
    private val keyVersionAvailable: (Int) -> Boolean = { true },
    private val metrics: EventSourcedCommandMetrics = EventSourcedCommandMetrics.NONE,
) {
    fun execute(command: EventSourcedCommand): CommandExecutionResult {
        val started = TimeSource.Monotonic.markNow()
        val acquired =
            transactions.inTransaction {
                receipts.acquire(command.scope, command.fingerprint, command.acquiredAt)
            }
        val result = when (acquired) {
            is ReceiptAcquireResult.Owner -> completeOwner(command, acquired)
            is ReceiptAcquireResult.Replay ->
                when (val replay = acquired.descriptor.replayWith(keyVersionAvailable)) {
                    is TerminalReplay.Replay -> CommandExecutionResult.Replayed(replay.descriptor)
                    TerminalReplay.KeyUnavailable -> CommandExecutionResult.KeyUnavailable()
                }
            is ReceiptAcquireResult.InProgress -> CommandExecutionResult.InProgress()
            ReceiptAcquireResult.FingerprintConflict -> CommandExecutionResult.FingerprintConflict()
        }
        val duration = started.elapsedNow().toJavaDuration()
        metrics.commandTerminal(result.status(), duration)
        if (result is CommandExecutionResult.Executed && result.append != null) {
            val eventCount = result.append.lastGlobalPosition - result.append.firstGlobalPosition + 1
            metrics.appendCommitted(eventCount.toInt(), duration)
        }
        return result
    }

    private fun completeOwner(
        command: EventSourcedCommand,
        owner: ReceiptAcquireResult.Owner,
    ): CommandExecutionResult {
        return transactions.inTransaction {
            check(receipts.isOwner(command.scope, command.fingerprint, owner.token, command.acquiredAt)) {
                "receipt ownership was lost before append"
            }
            val decision = command.decideAfterRehydrate()
            val append =
                if (decision.appends.isEmpty()) {
                    null
                } else {
                    eventStore.appendAll(decision.appends)
                }
            val descriptor =
                when (append) {
                    is AppendResult.Appended -> decision.descriptor.withStreamPosition(append.lastGlobalPosition)
                    is AppendResult.Conflict, is AppendResult.DuplicateEvent -> concurrentModificationDescriptor()
                    null -> decision.descriptor
                }
            finalize(command, owner, descriptor)
            log.debug { "voucher_command_executed operation=${command.scope.operation}" }
            CommandExecutionResult.Executed(append as? AppendResult.Appended, descriptor)
        }
    }

    private fun finalize(
        command: EventSourcedCommand,
        owner: ReceiptAcquireResult.Owner,
        descriptor: TerminalDescriptor,
    ) {
        check(receipts.finalize(command.scope, command.fingerprint, owner.token, command.acquiredAt, descriptor)) {
            "receipt ownership was lost before terminal finalize"
        }
    }

    private fun concurrentModificationDescriptor() =
        TerminalDescriptor(
            outcome = ReceiptOutcome.CONCURRENT_MODIFICATION,
            status = CONFLICT_STATUS,
        )

    companion object : KLogging() {
        private const val CONFLICT_STATUS = 409
    }
}

internal interface EventSourcedCommandMetrics {
    fun commandTerminal(
        status: Int,
        duration: Duration,
    )

    fun appendCommitted(
        eventCount: Int,
        duration: Duration,
    )

    companion object {
        val NONE =
            object : EventSourcedCommandMetrics {
                override fun commandTerminal(
                    status: Int,
                    duration: Duration,
                ) = Unit

                override fun appendCommitted(
                    eventCount: Int,
                    duration: Duration,
                ) = Unit
            }
    }
}

private fun CommandExecutionResult.status(): Int =
    when (this) {
        is CommandExecutionResult.Executed -> descriptor.status
        is CommandExecutionResult.Replayed -> descriptor.status
        is CommandExecutionResult.InProgress -> CONFLICT_STATUS
        is CommandExecutionResult.FingerprintConflict -> CONFLICT_STATUS
        is CommandExecutionResult.KeyUnavailable -> UNAVAILABLE_STATUS
    }

private const val CONFLICT_STATUS = 409
private const val UNAVAILABLE_STATUS = 503
