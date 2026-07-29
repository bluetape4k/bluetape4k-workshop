package io.bluetape4k.workshop.commerce.voucher.reconciliation

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.InboxStatus
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import java.io.Serializable
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal data class DelayedVoucherEvent(
    val tenantId: String,
    val eventId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val payloadDigest: String,
    val observedSequence: Long,
) {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= 64) { "tenantId must contain 1..64 characters" }
        require(aggregateType.isNotBlank() && aggregateType.length <= 32) {
            "aggregateType must contain 1..32 characters"
        }
        require(payloadDigest.matches(Regex("[0-9a-fA-F]{64}"))) { "payloadDigest must be a SHA-256 hex digest" }
        require(observedSequence >= 0) { "observedSequence must not be negative" }
    }
}

internal enum class InboxOutcome {
    APPLIED,
    IGNORED,
    CONFLICT,
    PENDING,
    FAILED,
}

internal data class InboxAcceptance(
    val inboxId: Long,
    val outcome: InboxOutcome,
) : Serializable

internal data class ReconciliationResult(
    val processed: Int,
    val skipped: Int,
    val failed: Int,
    val lastCursor: String?,
    val deadlineReached: Boolean,
) : Serializable

/** delayed event effect가 적용된 뒤에만 발행되는 안정적이고 민감하지 않은 event입니다. */
internal data class VoucherInboxAppliedEvent(
    val eventId: UUID,
    val tenantId: String,
    val aggregateType: String,
    val aggregateId: UUID,
    val revision: Long,
) : Serializable

internal fun interface VoucherEventPublisher {
    fun publish(event: VoucherInboxAppliedEvent)

    companion object {
        val NONE = VoucherEventPublisher {}
    }
}

internal class SpringVoucherEventPublisher(
    private val events: ApplicationEventPublisher,
) : VoucherEventPublisher {
    override fun publish(event: VoucherInboxAppliedEvent) {
        events.publishEvent(event)
    }
}

internal sealed interface DelayedEventDecision {
    data object Apply : DelayedEventDecision

    data object Ignore : DelayedEventDecision

    data object Conflict : DelayedEventDecision

    data class Retry(val reasonCode: String) : DelayedEventDecision {
        init {
            require(reasonCode.isNotBlank() && reasonCode.length <= 64) {
                "reasonCode must contain 1..64 characters"
            }
        }
    }
}

internal fun interface VoucherDelayedEventHandler {
    fun handle(record: EventInboxRecord): DelayedEventDecision
}

/** durable application fixture입니다. 적용된 delayed event는 append-only audit effect 하나를 만듭니다. */
internal class AuditingVoucherDelayedEventHandler(
    private val audits: AuditRepository,
    private val events: VoucherEventPublisher = VoucherEventPublisher.NONE,
) : VoucherDelayedEventHandler {
    override fun handle(record: EventInboxRecord): DelayedEventDecision {
        audits.append(
            AuditRecord(
                id = 0,
                tenantId = record.tenantId,
                campaignId = record.aggregateId,
                aggregateType = "DELAYED_${record.aggregateType}".take(32),
                aggregateId = record.aggregateId,
                revision = record.observedSequence,
                actorType = "EXTERNAL_EVENT",
                reasonCode = "DELAYED_EVENT_APPLIED",
                policyVersion = 0,
                correlationDigest = record.payloadDigest,
            ),
        )
        events.publish(
            VoucherInboxAppliedEvent(
                eventId = record.eventId,
                tenantId = record.tenantId,
                aggregateType = record.aggregateType,
                aggregateId = record.aggregateId,
                revision = record.observedSequence,
            ),
        )
        return DelayedEventDecision.Apply
    }
}

/** reconciliation transaction이 commit된 뒤 Modulith publication을 비동기로 완료합니다. */
internal open class VoucherInboxAppliedEventListener {
    @ApplicationModuleListener
    open fun on(event: VoucherInboxAppliedEvent) {
        log.info {
            "voucher_inbox_event_delivered eventId=${event.eventId} aggregateType=${event.aggregateType} " +
                "aggregateId=${event.aggregateId} revision=${event.revision}"
        }
    }

    companion object : KLogging()
}

/** authoritative effect 이후, inbox finalization 이전에 둔 test seam입니다. */
internal fun interface ReconciliationFaultInjector {
    fun afterEffect(record: EventInboxRecord)

    companion object {
        val NONE = ReconciliationFaultInjector {}
    }
}

/**
 * transaction 하나당 inbox row 하나를 reconcile해 claim, effect, terminal outcome이 atomic하게 commit되도록 합니다.
 * Redis와 leader state는 decision에 절대 참여하지 않습니다.
 */
internal class VoucherReconciliationService(
    private val jdbc: VoucherJdbcExecutor,
    private val inbox: EventInboxRepository,
    private val handler: VoucherDelayedEventHandler,
    private val clock: Clock,
    private val transactionTimeout: Duration,
    private val claimOwner: String = "voucher-reconciliation",
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val faultInjector: ReconciliationFaultInjector = ReconciliationFaultInjector.NONE,
) {
    init {
        require(!transactionTimeout.isNegative && !transactionTimeout.isZero) {
            "transactionTimeout must be positive"
        }
        require(claimOwner.isNotBlank() && claimOwner.length <= 128) { "claimOwner must contain 1..128 characters" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    fun accept(event: DelayedVoucherEvent): InboxAcceptance =
        jdbc.foregroundTransaction {
            val now = clock.instant()
            val insertion =
                inbox.insertIfAbsent(
                    EventInboxRecord(
                        id = 0,
                        tenantId = event.tenantId,
                        eventId = event.eventId,
                        aggregateType = event.aggregateType,
                        aggregateId = event.aggregateId,
                        payloadDigest = event.payloadDigest.lowercase(),
                        observedSequence = event.observedSequence,
                        status = InboxStatus.PENDING,
                        attempt = 0,
                        nextAttemptAt = now,
                        claimOwner = null,
                        claimUntil = null,
                    ),
                )
            if (!insertion.inserted) {
                val outcome =
                    if (insertion.record.payloadDigest == event.payloadDigest.lowercase()) {
                        InboxOutcome.IGNORED
                    } else {
                        InboxOutcome.CONFLICT
                    }
                log.info { "voucher_inbox_duplicate outcome=$outcome inboxId=${insertion.record.id}" }
                return@foregroundTransaction InboxAcceptance(insertion.record.id, outcome)
            }

            val claimed = inbox.claimById(insertion.record.id, now, claimOwner, transactionTimeout)
            val completed = applyClaimed(claimed, now, now.plus(transactionTimeout))
            InboxAcceptance(completed.record.id, completed.outcome)
        }

    fun runBatch(
        batchSize: Int = DEFAULT_BATCH_SIZE,
        deadline: Duration = DEFAULT_RUN_DEADLINE,
    ): ReconciliationResult {
        require(batchSize in 1..MAX_BATCH_SIZE) { "batchSize must contain 1..$MAX_BATCH_SIZE" }
        require(!deadline.isNegative) { "deadline must not be negative" }
        if (deadline.isZero) return ReconciliationResult(0, 0, 0, null, true)

        val runEndsAt = clock.instant().plus(deadline)
        var processed = 0
        var skipped = 0
        var failed = 0
        var lastCursor: String? = null
        var deadlineReached = false

        while (processed + skipped + failed < batchSize) {
            val remaining = Duration.between(clock.instant(), runEndsAt)
            if (!remaining.isPositive()) {
                deadlineReached = true
                break
            }
            val rowTimeout = minOf(remaining, transactionTimeout)
            val result =
                try {
                    jdbc.workerTransaction(rowTimeout) {
                        ensureBefore(runEndsAt)
                        val claimed = inbox.claimNext(clock.instant(), claimOwner, rowTimeout)
                            ?: return@workerTransaction RowResult.NoWork
                        applyClaimed(claimed, clock.instant(), runEndsAt)
                    }
                } catch (_: ReconciliationDeadlineExceeded) {
                    deadlineReached = true
                    break
                }
            when (result) {
                RowResult.NoWork -> break
                is RowResult.Completed -> {
                    lastCursor = cursorOf(result.record.id)
                    when (result.outcome) {
                        InboxOutcome.APPLIED -> processed++
                        InboxOutcome.IGNORED, InboxOutcome.CONFLICT -> skipped++
                        InboxOutcome.PENDING, InboxOutcome.FAILED -> failed++
                    }
                }
            }
        }

        val result = ReconciliationResult(processed, skipped, failed, lastCursor, deadlineReached)
        log.info {
            "voucher_reconciliation_completed processed=$processed skipped=$skipped failed=$failed " +
                "deadlineReached=$deadlineReached"
        }
        return result
    }

    private fun applyClaimed(
        claimed: EventInboxRecord,
        now: Instant,
        runEndsAt: Instant,
    ): RowResult.Completed {
        inbox.lockAggregate(claimed)
        ensureBefore(runEndsAt)
        val latestApplied = inbox.latestAppliedSequence(claimed)
        val decision =
            when {
                latestApplied == claimed.observedSequence -> DelayedEventDecision.Ignore
                latestApplied != null && latestApplied > claimed.observedSequence -> DelayedEventDecision.Conflict
                else -> handler.handle(claimed)
            }
        if (decision is DelayedEventDecision.Apply) {
            faultInjector.afterEffect(claimed)
            ensureBefore(runEndsAt)
        }
        val completed =
            when (decision) {
                DelayedEventDecision.Apply -> inbox.complete(claimed.id, InboxStatus.APPLIED)
                DelayedEventDecision.Ignore -> inbox.complete(claimed.id, InboxStatus.IGNORED)
                DelayedEventDecision.Conflict -> inbox.complete(claimed.id, InboxStatus.CONFLICT)
                is DelayedEventDecision.Retry -> {
                    val backoff = backoffFor(claimed.attempt + 1)
                    log.warn {
                        "voucher_inbox_retry inboxId=${claimed.id} attempt=${claimed.attempt + 1} " +
                            "reason=${decision.reasonCode}"
                    }
                    inbox.retry(claimed, now, now.plus(backoff), maxAttempts)
                }
            }
        return RowResult.Completed(completed, completed.status.toOutcome())
    }

    private fun ensureBefore(deadline: Instant) {
        if (!clock.instant().isBefore(deadline)) throw ReconciliationDeadlineExceeded()
    }

    private fun backoffFor(attempt: Int): Duration =
        Duration.ofSeconds(1L shl (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT))

    private fun cursorOf(id: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(id).array())

    private fun InboxStatus.toOutcome(): InboxOutcome =
        when (this) {
            InboxStatus.APPLIED -> InboxOutcome.APPLIED
            InboxStatus.IGNORED -> InboxOutcome.IGNORED
            InboxStatus.CONFLICT -> InboxOutcome.CONFLICT
            InboxStatus.PENDING, InboxStatus.CLAIMED -> InboxOutcome.PENDING
            InboxStatus.FAILED -> InboxOutcome.FAILED
        }

    private sealed interface RowResult {
        data object NoWork : RowResult

        data class Completed(
            val record: EventInboxRecord,
            val outcome: InboxOutcome,
        ) : RowResult
    }

    private class ReconciliationDeadlineExceeded : RuntimeException()

    companion object : KLogging() {
        private const val DEFAULT_BATCH_SIZE = 50
        private const val MAX_BATCH_SIZE = 50
        private const val DEFAULT_MAX_ATTEMPTS = 5
        private const val MAX_BACKOFF_SHIFT = 5
        private val DEFAULT_RUN_DEADLINE: Duration = Duration.ofSeconds(10)
    }
}
