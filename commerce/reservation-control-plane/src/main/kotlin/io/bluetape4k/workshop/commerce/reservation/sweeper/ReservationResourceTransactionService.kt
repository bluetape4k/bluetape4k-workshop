package io.bluetape4k.workshop.commerce.reservation.sweeper

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCapacityHandoffService
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRepository
import io.bluetape4k.workshop.commerce.reservation.redis.DatabaseBulkheadOutcome
import io.bluetape4k.workshop.commerce.reservation.redis.DatabaseWorkload
import io.bluetape4k.workshop.commerce.reservation.redis.NodeLocalDatabaseBulkhead
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * expired hold와 offer를 resource transaction 하나씩 finalize합니다.
 *
 * 각 transaction은 resource를 먼저 lock하고, revision CAS transition을 적용하고, audit row를 기록한 뒤,
 * commit 전에 FIFO head를 promote하거나 capacity를 release합니다.
 * @property resources 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property holds 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property offers 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property waitlists 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property handoff 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audits 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Service
internal class ReservationResourceTransactionService(
    /**
     * `resources`은 예약 제어면에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    private val resources: CapacityResourceRepository,
    /**
     * `holds`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val holds: ReservationHoldRepository,
    /**
     * `offers`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val offers: ReservationOfferRepository,
    /**
     * `waitlists`은 예약 제어면에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val waitlists: WaitlistEntryRepository,
    /**
     * `handoff`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val handoff: ReservationCapacityHandoffService,
    /**
     * `audits`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audits: ReservationAuditRepository,
) {

    companion object: KLogging()

    /** deduplication과 batch limiting 전에 hold와 offer expiry stream을 전역적으로 병합합니다. */
    /** deduplication과 batch limiting 전에 hold와 offer expiry stream을 전역적으로 병합합니다. */
    @Transactional(readOnly = true)
    fun expiredResourceIds(
        now: Instant,
        limit: Int,
    ): List<Long> =
        (holds.expiredResourceCandidates(now, limit) + offers.expiredResourceCandidates(now, limit))
            .sortedWith(compareBy({ it.expiresAt }, { it.resourceId }))
            .distinctBy { it.resourceId }
            .take(limit)
            .map { it.resourceId }

    /** canonical resource -> hold -> waitlist -> outbox lock order로 resource 하나를 finalize합니다. */
    /** canonical resource -> hold -> waitlist -> outbox lock order로 resource 하나를 finalize합니다. */
    @Transactional
    fun finalizeExpiredResource(
        resourceId: Long,
        now: Instant,
    ): SweepBatchSummary {
        resources.findByIdForUpdate(resourceId)
        var expired = 0
        var promoted = 0
        var stale = 0

        holds.expiredForResource(resourceId, now).forEach { hold ->
            val transitioned = holds.transition(
                id = hold.id,
                ownerDigest = hold.ownerDigest,
                expectedRevision = hold.revision,
                from = HoldState.HELD,
                to = HoldState.EXPIRED
            )
            if (!transitioned) {
                stale += 1
                return@forEach
            }
            expired += 1
            audits.record(
                aggregateType = "RESERVATION_HOLD",
                aggregateId = hold.id,
                revision = hold.revision + 1,
                outcome = HoldState.EXPIRED.name
            )
            if (handoff.promoteOrRelease(resourceId, now) != null) {
                promoted += 1
            }
        }
        offers.expiredForResource(resourceId, now).forEach { offer ->
            if (!offers.transition(
                    id = offer.id,
                    ownerDigest = offer.ownerDigest,
                    expectedRevision = offer.revision,
                    from = OfferState.ACTIVE,
                    to = OfferState.EXPIRED
                )
            ) {
                stale += 1
                return@forEach
            }
            val entry = waitlists.findById(offer.entryId)
            waitlists.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = entry.revision,
                from = WaitlistState.OFFERED,
                to = WaitlistState.EXPIRED
            ).checkBeTrue { "expired offer waitlist transition lost" }

            audits.record(
                aggregateType = "RESERVATION_OFFER",
                aggregateId = offer.id,
                revision = offer.revision + 1,
                outcome = OfferState.EXPIRED.name
            )
            audits.record(
                aggregateType = "WAITLIST_ENTRY",
                aggregateId = entry.id,
                revision = entry.revision + 1,
                outcome = WaitlistState.EXPIRED.name
            )
            if (handoff.promoteOrRelease(resourceId, now) != null) {
                promoted += 1
            }
        }
        log.debug {
            "reservation_resource_finalized resourceId=$resourceId expired=$expired promoted=$promoted stale=$stale"
        }
        return SweepBatchSummary(
            scannedResources = 1,
            expiredHolds = expired,
            promotedEntries = promoted,
            staleConflicts = stale
        )
    }
}

/**
 * background JDBC bulkhead 뒤에서 bounded PostgreSQL sweep work를 실행합니다.
 * @property transactions 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property bulkhead 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
internal class PostgresReservationSweepWork(
    /**
     * `transactions`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions: ReservationResourceTransactionService,
    /**
     * `bulkhead`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val bulkhead: NodeLocalDatabaseBulkhead,
    /**
     * `clock`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
): ReservationSweepWork {

    companion object: KLogging()

    /**
     * sweep은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param maxResources 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param budget 업무 처리에 필요한 budget 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun sweep(
        maxResources: Int,
        budget: Duration,
    ): SweepBatchSummary {
        val now = clock.instant()
        val deadline = System.nanoTime() + budget.toNanos()
        return when (
            val outcome =
                bulkhead.execute(DatabaseWorkload.BACKGROUND) {
                    val resourceIds = transactions.expiredResourceIds(now, maxResources)
                    resourceIds
                        .takeWhile { System.nanoTime() < deadline }
                        .map { transactions.finalizeExpiredResource(it, now) }
                        .fold(SweepBatchSummary.Empty, SweepBatchSummary::plus)
                }
        ) {
            is DatabaseBulkheadOutcome.Executed -> {
                outcome.value
            }
            is DatabaseBulkheadOutcome.Rejected -> {
                SweepBatchSummary.Empty.also {
                    log.debug { "reservation_sweep_background_busy outcome=SKIPPED" }
                }
            }
        }
    }
}

/**
 * plus은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private operator fun SweepBatchSummary.plus(other: SweepBatchSummary): SweepBatchSummary =
    SweepBatchSummary(
        scannedResources + other.scannedResources,
        expiredHolds + other.expiredHolds,
        promotedEntries + other.promotedEntries,
        staleConflicts + other.staleConflicts
    )
