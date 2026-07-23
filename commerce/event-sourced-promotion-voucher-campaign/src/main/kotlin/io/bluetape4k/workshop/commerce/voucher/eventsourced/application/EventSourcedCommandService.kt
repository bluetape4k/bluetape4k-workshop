package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.EventSourcedIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptAcquireResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptScope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalDescriptor
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

/** Owns the two transaction cuts: a committed receipt acquire, then atomic append plus terminal finalize. */
internal interface CommandTransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}

internal class ExposedCommandTransactionRunner(
    private val database: Database,
) : CommandTransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = transaction(database) { block() }
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
}

internal class EventSourcedCommandService(
    private val transactions: CommandTransactionRunner,
    private val receipts: EventSourcedIdempotencyRepository,
    private val eventStore: EventStorePort,
) {
    fun execute(command: EventSourcedCommand): CommandExecutionResult {
        val acquired =
            transactions.inTransaction {
                receipts.acquire(command.scope, command.fingerprint, command.acquiredAt)
            }
        return when (acquired) {
            is ReceiptAcquireResult.Owner -> completeOwner(command, acquired)
            is ReceiptAcquireResult.Replay -> CommandExecutionResult.Replayed(acquired.descriptor)
            is ReceiptAcquireResult.InProgress -> CommandExecutionResult.InProgress()
            ReceiptAcquireResult.FingerprintConflict -> CommandExecutionResult.FingerprintConflict()
        }
    }

    private fun completeOwner(
        command: EventSourcedCommand,
        owner: ReceiptAcquireResult.Owner,
    ): CommandExecutionResult {
        val decision = command.decideAfterRehydrate()
        return transactions.inTransaction {
            check(receipts.isOwner(command.scope, command.fingerprint, owner.token, command.acquiredAt)) {
                "receipt ownership was lost before append"
            }
            val append =
                if (decision.appends.isEmpty()) {
                    null
                } else {
                    eventStore.appendAll(decision.appends)
                }
            val descriptor = if (append is AppendResult.Conflict || append is AppendResult.DuplicateEvent) {
                concurrentModificationDescriptor()
            } else {
                decision.descriptor
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
