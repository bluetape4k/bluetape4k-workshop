package io.bluetape4k.workshop.commerce.voucher.reconciliation

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
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
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * VoucherReconciliationServiceTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherReconciliationServiceTest: VoucherCommandTestSupport() {

    companion object: KLogging()

/**
 * mutableClock은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var mutableClock: MutableClock
/**
 * inbox은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var inbox: EventInboxRepository
/**
 * reconciliation은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var reconciliation: VoucherReconciliationService

    @BeforeEach
    fun createReconciliationRuntime() {
        mutableClock = MutableClock(NOW)
        configureCommandRuntime(workerPermits = 2, serviceClock = mutableClock)
        inbox = EventInboxRepository(gate)
        reconciliation = service(AuditingVoucherDelayedEventHandler(audits))
    }

    @Test
/**
 * 이 시나리오는 "duplicate delayed event is applied once and reported as ignored on replay" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `duplicate delayed event is applied once and reported as ignored on replay`() {
        val event = delayedEvent(sequence = 3)

        reconciliation.accept(event).outcome shouldBeEqualTo InboxOutcome.APPLIED
        reconciliation.accept(event).outcome shouldBeEqualTo InboxOutcome.IGNORED
        auditCount() shouldBeEqualTo 1L
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.APPLIED
    }

    @Test
/**
 * 이 시나리오는 "stale out of order event is retained as conflict" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stale out of order event is retained as conflict`() {
        val aggregateId = Uuid.V7.nextId()

        reconciliation
            .accept(delayedEvent(aggregateId = aggregateId, sequence = 3))
            .outcome shouldBeEqualTo InboxOutcome.APPLIED

        val stale = delayedEvent(aggregateId = aggregateId, sequence = 2)

        reconciliation.accept(stale).outcome shouldBeEqualTo InboxOutcome.CONFLICT
        inboxRecord(stale.eventId).status shouldBeEqualTo InboxStatus.CONFLICT
        auditCount() shouldBeEqualTo 1L
    }

    @Test
/**
 * 이 시나리오는 "same event id with another payload reports conflict without changing the committed
 * effect" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same event id with another payload reports conflict without changing the committed effect`() {
        val event = delayedEvent()
        reconciliation.accept(event).outcome shouldBeEqualTo InboxOutcome.APPLIED
        val conflicting = event.copy(payloadDigest = "f".repeat(64))

        reconciliation.accept(conflicting).outcome shouldBeEqualTo InboxOutcome.CONFLICT
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.APPLIED
        auditCount() shouldBeEqualTo 1L
    }

    @Test
/**
 * 이 시나리오는 "worker overlap claims each inbox row once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `worker overlap claims each inbox row once`() {
        repeat(75) { seedPending(delayedEvent(aggregateId = Uuid.V7.nextId(), sequence = 1)) }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        val results = VirtualThreads.executorService().use { executor ->
            List(2) {
                executor.submit<ReconciliationResult> {
                    ready.countDown()
                    check(start.await(2.seconds))
                    reconciliation.runBatch(50, 10.seconds())
                }
            }.also {
                check(ready.await(2.seconds))
                start.countDown()
            }.map { it.get(15.seconds) }
        }

        results.sumOf { it.processed } shouldBeEqualTo 75
        eventInboxCount(InboxStatus.APPLIED) shouldBeEqualTo 75L
        distinctEventInboxCount(InboxStatus.APPLIED) shouldBeEqualTo 75L
        auditCount() shouldBeEqualTo 75L
    }

    @Test
/**
 * 이 시나리오는 "poison row backs off without starving the next row and becomes failed after five
 * attempts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `poison row backs off without starving the next row and becomes failed after five attempts`() {
        val poison = delayedEvent(aggregateId = Uuid.V7.nextId())
        val valid = delayedEvent(aggregateId = Uuid.V7.nextId())
        seedPending(poison)
        seedPending(valid)

        reconciliation = service(
            { record ->
                if (record.eventId == poison.eventId) {
                    DelayedEventDecision.Retry("POISON_FIXTURE")
                } else {
                    AuditingVoucherDelayedEventHandler(audits).handle(record)
                }
            },
        )

        repeat(5) { index ->
            reconciliation.runBatch(50, 10.seconds())
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
/**
 * 이 시나리오는 "zero deadline claims no inbox row" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `zero deadline claims no inbox row`() {
        repeat(3) { seedPending(delayedEvent(aggregateId = Uuid.V7.nextId())) }

        val result = reconciliation.runBatch(50, Duration.ZERO)

        result.deadlineReached.shouldBeTrue()
        result.processed shouldBeEqualTo 0
        eventInboxCount(InboxStatus.PENDING) shouldBeEqualTo 3L
    }

    @Test
/**
 * 이 시나리오는 "effect rollback is reprocessed exactly once by a fresh service" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `effect rollback is reprocessed exactly once by a fresh service`() {
        val event = delayedEvent()
        seedPending(event)
        val failOnce = AtomicBoolean(true)

        reconciliation = service(AuditingVoucherDelayedEventHandler(audits)) {
            if (failOnce.compareAndSet(true, false)) throw InjectedWorkerFailure()
        }

        assertFailsWith<InjectedWorkerFailure> {
            reconciliation.runBatch(50, 10.seconds())
        }
        auditCount() shouldBeEqualTo 0L
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.PENDING

        val restarted = service(AuditingVoucherDelayedEventHandler(audits))
        restarted.runBatch(50, 10.seconds()).processed shouldBeEqualTo 1

        auditCount() shouldBeEqualTo 1L
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.APPLIED
    }

    @Test
/**
 * 이 시나리오는 "row transaction cannot commit after a positive run deadline" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `row transaction cannot commit after a positive run deadline`() {
        val event = delayedEvent()
        seedPending(event)
        reconciliation = service(AuditingVoucherDelayedEventHandler(audits)) {
            mutableClock.advance(201.millis())
        }

        val result = reconciliation.runBatch(50, 200.millis())

        result.deadlineReached.shouldBeTrue()
        auditCount() shouldBeEqualTo 0L
        inboxRecord(event.eventId).status shouldBeEqualTo InboxStatus.PENDING

        service(AuditingVoucherDelayedEventHandler(audits))
            .runBatch(50, 10.seconds())
            .processed shouldBeEqualTo 1
    }

/**
 * service은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun service(
        handler: VoucherDelayedEventHandler,
        faultInjector: ReconciliationFaultInjector = ReconciliationFaultInjector.NONE,
    ): VoucherReconciliationService =
        VoucherReconciliationService(
            jdbc = jdbc,
            inbox = inbox,
            handler = handler,
            clock = mutableClock,
            transactionTimeout = 2.seconds(),
            faultInjector = faultInjector,
        )

/**
 * seedPending은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * inboxRecord은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun inboxRecord(eventId: UUID): EventInboxRecord =
        jdbc.foregroundTransaction {
            inbox.findEvent(TENANT_ID, eventId).shouldNotBeNull()
        }

/**
 * delayedEvent은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun delayedEvent(
        eventId: UUID = Uuid.V7.nextId(),
        aggregateId: UUID = Uuid.V7.nextId(),
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

/**
 * expectedBackoff은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun expectedBackoff(attempt: Int): Duration = (1L shl (attempt - 1)).seconds()

/**
 * InjectedWorkerFailure 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class InjectedWorkerFailure: RuntimeException()
}

/**
 * MutableClock 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class MutableClock(
    initial: Instant,
): Clock() {
    @Volatile
/**
 * current은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var current = initial

/**
 * getZone은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getZone(): ZoneId = ZoneOffset.UTC

/**
 * withZone은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun withZone(zone: ZoneId): Clock = this

/**
 * instant은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun instant(): Instant = current

/**
 * advance은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
