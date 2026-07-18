package io.bluetape4k.workshop.commerce.reservation.sweeper

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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

@Service
internal class ReservationResourceTransactionService(
    private val resources: CapacityResourceRepository,
    private val holds: ReservationHoldRepository,
    private val offers: ReservationOfferRepository,
    private val waitlists: WaitlistEntryRepository,
    private val handoff: ReservationCapacityHandoffService,
    private val audits: ReservationAuditRepository,
) {
    @Transactional(readOnly = true)
    fun expiredResourceIds(now: Instant, limit: Int): List<Long> =
        (holds.expiredResourceCandidates(now, limit) + offers.expiredResourceCandidates(now, limit))
            .sortedWith(compareBy({ it.expiresAt }, { it.resourceId }))
            .distinctBy { it.resourceId }
            .take(limit)
            .map { it.resourceId }

    /** Finalizes one resource using the canonical resource -> hold -> waitlist -> outbox lock order. */
    @Transactional
    fun finalizeExpiredResource(resourceId: Long, now: Instant): SweepBatchSummary {
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
                to = HoldState.EXPIRED,
            )
            if (!transitioned) {
                stale += 1
                return@forEach
            }
            expired += 1
            audits.record("RESERVATION_HOLD", hold.id, hold.revision + 1, HoldState.EXPIRED.name)
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
                    to = OfferState.EXPIRED,
                )
            ) {
                stale += 1
                return@forEach
            }
            val entry = waitlists.findById(offer.entryId)
            check(waitlists.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = entry.revision,
                from = WaitlistState.OFFERED,
                to = WaitlistState.EXPIRED,
            )) { "expired offer waitlist transition lost" }
            audits.record("RESERVATION_OFFER", offer.id, offer.revision + 1, OfferState.EXPIRED.name)
            audits.record("WAITLIST_ENTRY", entry.id, entry.revision + 1, WaitlistState.EXPIRED.name)
            if (handoff.promoteOrRelease(resourceId, now) != null) {
                promoted += 1
            }
        }
        log.debug {
            "reservation_resource_finalized resourceId=$resourceId expired=$expired promoted=$promoted stale=$stale"
        }
        return SweepBatchSummary(1, expired, promoted, stale)
    }

    companion object : KLogging()
}

@Component
internal class PostgresReservationSweepWork(
    private val transactions: ReservationResourceTransactionService,
    private val bulkhead: NodeLocalDatabaseBulkhead,
    private val clock: Clock,
) : ReservationSweepWork {
    override fun sweep(maxResources: Int, budget: Duration): SweepBatchSummary {
        val now = clock.instant()
        val deadline = System.nanoTime() + budget.toNanos()
        return when (val outcome = bulkhead.execute(DatabaseWorkload.BACKGROUND) {
            val resourceIds = transactions.expiredResourceIds(now, maxResources)
            resourceIds.takeWhile { System.nanoTime() < deadline }
                .map { transactions.finalizeExpiredResource(it, now) }
                .fold(SweepBatchSummary.Empty, SweepBatchSummary::plus)
        }) {
            is DatabaseBulkheadOutcome.Executed -> outcome.value
            is DatabaseBulkheadOutcome.Rejected -> SweepBatchSummary.Empty.also {
                log.debug { "reservation_sweep_background_busy outcome=SKIPPED" }
            }
        }
    }

    companion object : KLogging()
}

private operator fun SweepBatchSummary.plus(other: SweepBatchSummary) = SweepBatchSummary(
    scannedResources + other.scannedResources,
    expiredHolds + other.expiredHolds,
    promotedEntries + other.promotedEntries,
    staleConflicts + other.staleConflicts,
)
