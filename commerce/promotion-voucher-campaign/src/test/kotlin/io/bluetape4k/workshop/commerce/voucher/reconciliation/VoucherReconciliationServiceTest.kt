package io.bluetape4k.workshop.commerce.voucher.reconciliation

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandTestSupport
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.InboxStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class VoucherReconciliationServiceTest : VoucherCommandTestSupport() {
    private lateinit var mutableClock: MutableClock
    private lateinit var inbox: EventInboxRepository
    private lateinit var reconciliation: VoucherReconciliationService

    @BeforeEach
    fun createReconciliationRuntime() {
        mutableClock = MutableClock(NOW)
        configureCommandRuntime(workerPermits = 2, serviceClock = mutableClock)
        inbox = EventInboxRepository(gate)
        reconciliation = service(AuditingVoucherDelayedEventHandler(audits))
    }

    @Test
    fun `duplicate delayed event is applied once and reported as ignored on replay`() {
        val event = delayedEvent(sequence = 3)

        reconciliation.accept(event).outcome shouldBeEqualTo InboxOutcome.APPLIED
        reconciliation.accept(event).outcome shouldBeEqualTo InboxOutcome.IGNORED
        queryLong("SELECT count(*) FROM voucher_audits") shouldBeEqualTo 1L
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.APPLIED
    }

    @Test
    fun `stale out of order event is retained as conflict`() {
        val aggregateId = UUID.randomUUID()
        reconciliation.accept(delayedEvent(aggregateId = aggregateId, sequence = 3)).outcome shouldBeEqualTo
            InboxOutcome.APPLIED
        val stale = delayedEvent(aggregateId = aggregateId, sequence = 2)

        reconciliation.accept(stale).outcome shouldBeEqualTo InboxOutcome.CONFLICT
        inboxRecord(stale.eventId).status shouldBeEqualTo InboxStatus.CONFLICT
        queryLong("SELECT count(*) FROM voucher_audits") shouldBeEqualTo 1L
    }

    @Test
    fun `same event id with another payload reports conflict without changing the committed effect`() {
        val event = delayedEvent()
        reconciliation.accept(event).outcome shouldBeEqualTo InboxOutcome.APPLIED
        val conflicting = event.copy(payloadDigest = "f".repeat(64))

        reconciliation.accept(conflicting).outcome shouldBeEqualTo InboxOutcome.CONFLICT
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.APPLIED
        queryLong("SELECT count(*) FROM voucher_audits") shouldBeEqualTo 1L
    }

    @Test
    fun `worker overlap claims each inbox row once`() {
        repeat(75) { seedPending(delayedEvent(aggregateId = UUID.randomUUID(), sequence = 1)) }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        val results =
            VirtualThreads.executorService().use { executor ->
                List(2) {
                    executor.submit<ReconciliationResult> {
                        ready.countDown()
                        check(start.await(2, TimeUnit.SECONDS))
                        reconciliation.runBatch(50, Duration.ofSeconds(10))
                    }
                }.also {
                    check(ready.await(2, TimeUnit.SECONDS))
                    start.countDown()
                }.map { it.get(15, TimeUnit.SECONDS) }
            }

        results.sumOf { it.processed } shouldBeEqualTo 75
        queryLong("SELECT count(*) FROM campaign_event_inbox WHERE status = 'APPLIED'") shouldBeEqualTo 75L
        queryLong("SELECT count(DISTINCT event_id) FROM campaign_event_inbox WHERE status = 'APPLIED'") shouldBeEqualTo 75L
        queryLong("SELECT count(*) FROM voucher_audits") shouldBeEqualTo 75L
    }

    @Test
    fun `poison row backs off without starving the next row and becomes failed after five attempts`() {
        val poison = delayedEvent(aggregateId = UUID.randomUUID())
        val valid = delayedEvent(aggregateId = UUID.randomUUID())
        seedPending(poison)
        seedPending(valid)
        reconciliation =
            service(
                VoucherDelayedEventHandler { record ->
                    if (record.eventId == poison.eventId) {
                        DelayedEventDecision.Retry("POISON_FIXTURE")
                    } else {
                        AuditingVoucherDelayedEventHandler(audits).handle(record)
                    }
                },
            )

        repeat(5) { index ->
            reconciliation.runBatch(50, Duration.ofSeconds(10))
            inboxRecord(valid.eventId).status shouldBeEqualTo InboxStatus.APPLIED
            val poisonRecord = inboxRecord(poison.eventId)
            poisonRecord.attempt shouldBeEqualTo index + 1
            poisonRecord.status shouldBeEqualTo if (index < 4) InboxStatus.PENDING else InboxStatus.FAILED
            if (index < 4) {
                poisonRecord.nextAttemptAt shouldBeEqualTo mutableClock.instant().plus(expectedBackoff(index + 1))
                mutableClock.advance(expectedBackoff(index + 1))
            }
        }
    }

    @Test
    fun `zero deadline claims no inbox row`() {
        repeat(3) { seedPending(delayedEvent(aggregateId = UUID.randomUUID())) }

        val result = reconciliation.runBatch(50, Duration.ZERO)

        assertTrue(result.deadlineReached)
        result.processed shouldBeEqualTo 0
        queryLong("SELECT count(*) FROM campaign_event_inbox WHERE status = 'PENDING'") shouldBeEqualTo 3L
    }

    @Test
    fun `effect rollback is reprocessed exactly once by a fresh service`() {
        val event = delayedEvent()
        seedPending(event)
        val failOnce = AtomicBoolean(true)
        reconciliation =
            service(
                AuditingVoucherDelayedEventHandler(audits),
                ReconciliationFaultInjector {
                    if (failOnce.compareAndSet(true, false)) throw InjectedWorkerFailure()
                },
            )

        assertFailsWith<InjectedWorkerFailure> {
            reconciliation.runBatch(50, Duration.ofSeconds(10))
        }
        queryLong("SELECT count(*) FROM voucher_audits") shouldBeEqualTo 0L
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.PENDING

        val restarted = service(AuditingVoucherDelayedEventHandler(audits))
        restarted.runBatch(50, Duration.ofSeconds(10)).processed shouldBeEqualTo 1
        queryLong("SELECT count(*) FROM voucher_audits") shouldBeEqualTo 1L
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.APPLIED
    }

    @Test
    fun `row transaction cannot commit after a positive run deadline`() {
        val event = delayedEvent()
        seedPending(event)
        reconciliation =
            service(
                AuditingVoucherDelayedEventHandler(audits),
                ReconciliationFaultInjector { mutableClock.advance(Duration.ofMillis(201)) },
            )

        val result = reconciliation.runBatch(50, Duration.ofMillis(200))

        assertTrue(result.deadlineReached)
        queryLong("SELECT count(*) FROM voucher_audits") shouldBeEqualTo 0L
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.PENDING
        service(AuditingVoucherDelayedEventHandler(audits))
            .runBatch(50, Duration.ofSeconds(10)).processed shouldBeEqualTo 1
    }

    private fun service(
        handler: VoucherDelayedEventHandler,
        faultInjector: ReconciliationFaultInjector = ReconciliationFaultInjector.NONE,
    ): VoucherReconciliationService =
        VoucherReconciliationService(
            jdbc = jdbc,
            inbox = inbox,
            handler = handler,
            clock = mutableClock,
            transactionTimeout = Duration.ofSeconds(2),
            faultInjector = faultInjector,
        )

    private fun seedPending(event: DelayedVoucherEvent) {
        jdbc.foregroundTransaction {
            inbox.insert(
                EventInboxRecord(
                    id = 0,
                    tenantId = event.tenantId,
                    eventId = event.eventId,
                    aggregateType = event.aggregateType,
                    aggregateId = event.aggregateId,
                    payloadDigest = event.payloadDigest,
                    observedSequence = event.observedSequence,
                    status = InboxStatus.PENDING,
                    attempt = 0,
                    nextAttemptAt = mutableClock.instant(),
                    claimOwner = null,
                    claimUntil = null,
                ),
            )
        }
    }

    private fun inboxRecord(eventId: UUID): EventInboxRecord =
        jdbc.foregroundTransaction { checkNotNull(inbox.findEvent(TENANT_ID, eventId)) }

    private fun delayedEvent(
        eventId: UUID = UUID.randomUUID(),
        aggregateId: UUID = UUID.randomUUID(),
        sequence: Long = 1,
    ): DelayedVoucherEvent =
        DelayedVoucherEvent(
            tenantId = TENANT_ID,
            eventId = eventId,
            aggregateType = "CAMPAIGN",
            aggregateId = aggregateId,
            payloadDigest = eventId.toString().replace("-", "").repeat(2),
            observedSequence = sequence,
        )

    private fun expectedBackoff(attempt: Int): Duration = Duration.ofSeconds(1L shl (attempt - 1))

    private class InjectedWorkerFailure : RuntimeException()
}

internal class MutableClock(
    initial: Instant,
) : Clock() {
    @Volatile
    private var current = initial

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
