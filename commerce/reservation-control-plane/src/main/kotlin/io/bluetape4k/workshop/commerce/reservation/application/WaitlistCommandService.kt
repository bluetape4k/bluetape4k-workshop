package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Clock
import java.time.Duration

/**
 * Coordinates FIFO waitlist entries and their short-lived offers.
 *
 * Mutations that can transfer occupied capacity lock the resource row before reading dependent
 * waitlist or offer rows. This makes promotion, acceptance, expiry, and release share one lock order.
 */
@Service
internal class WaitlistCommandService(
    private val waitlists: WaitlistEntryRepository,
    private val offers: ReservationOfferRepository,
    private val credentials: ReservationCredentialService,
    private val clock: Clock,
    @Value("\${reservation.offer.ttl:20s}") private val offerTtl: Duration = Duration.ofSeconds(20),
    private val resources: CapacityResourceRepository? = null,
    private val holds: ReservationHoldRepository? = null,
    private val audits: ReservationAuditRepository? = null,
) {
    @Transactional
    fun join(command: JoinWaitlistCommand): WaitlistEntryRecord {
        resources?.findByIdForUpdate(command.resourceId)?.also { resource ->
            if (resource.policyVersion != command.policyVersion) {
                throw WaitlistCommandException("POLICY_VERSION_MISMATCH", resource.revision)
            }
            if (resource.revision != command.expectedResourceRevision) {
                throw WaitlistCommandException("STALE_REVISION", resource.revision)
            }
            if (resource.occupiedCount < resource.capacity) {
                throw WaitlistCommandException("CAPACITY_AVAILABLE", resource.revision)
            }
        }
        val entry = waitlists.join(command.resourceId, credentials.ownerDigest(command.ownerToken))
        log.debug {
            "waitlist_join_applied entryId=${entry.id} resourceId=${entry.resourceId} sequence=${entry.sequence}"
        }
        audits?.record("WAITLIST_ENTRY", entry.id, entry.revision, entry.state.name)
        return entry
    }

    @Transactional(readOnly = true)
    fun snapshot(entryId: Long, ownerToken: String): WaitlistEntryRecord {
        val entry = waitlists.findById(entryId)
        requireOwner(ownerToken, entry.ownerDigest, entry.revision)
        return entry
    }

    @Transactional
    fun cancel(command: CancelWaitlistCommand): WaitlistEntryRecord {
        val entry = waitlists.findById(command.entryId)
        requireOwner(command.ownerToken, entry.ownerDigest, entry.revision)
        if (entry.state != WaitlistState.WAITING) {
            throw WaitlistCommandException("INVALID_WAITLIST_STATE", entry.revision)
        }
        if (entry.revision != command.expectedRevision) {
            throw WaitlistCommandException("STALE_REVISION", entry.revision)
        }
        if (!waitlists.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = command.expectedRevision,
                from = WaitlistState.WAITING,
                to = WaitlistState.CANCELLED,
            )
        ) {
            throw WaitlistCommandException("STALE_REVISION", entry.revision)
        }
        return waitlists.findById(entry.id).also { updated ->
            audits?.record("WAITLIST_ENTRY", updated.id, updated.revision, updated.state.name)
        }
    }

    /** Caller transaction should lock the resource before invoking this operation. */
    @Transactional
    fun promote(resourceId: Long): ReservationOfferRecord? {
        resources?.findByIdForUpdate(resourceId)
        val entry = waitlists.oldestWaiting(resourceId) ?: return null
        if (!waitlists.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = entry.revision,
                from = WaitlistState.WAITING,
                to = WaitlistState.OFFERED,
            )
        ) {
            log.debug { "waitlist_promotion_stale entryId=${entry.id} resourceId=$resourceId revision=${entry.revision}" }
            return null
        }
        val offer = offers.createActive(
            resourceId = resourceId,
            entryId = entry.id,
            ownerDigest = entry.ownerDigest,
            expiresAt = clock.instant().plus(offerTtl),
        )
        audits?.record("WAITLIST_ENTRY", entry.id, entry.revision + 1, WaitlistState.OFFERED.name)
        audits?.record("RESERVATION_OFFER", offer.id, offer.revision, offer.state.name)
        log.debug { "waitlist_promotion_applied entryId=${entry.id} offerId=${offer.id} resourceId=$resourceId" }
        return offer
    }

    @Transactional
    fun accept(command: AcceptOfferCommand): AcceptedOffer {
        val offerSnapshot = offers.findById(command.offerId)
        // Re-read the offer after taking the resource lock; the first read is only a lock locator.
        val resource = resources?.findByIdForUpdate(offerSnapshot.resourceId)
        val offer = offers.findById(command.offerId)
        requireOwner(command.ownerToken, offer.ownerDigest, offer.revision)
        if (offer.state != OfferState.ACTIVE) {
            throw WaitlistCommandException("INVALID_OFFER_STATE", offer.revision)
        }
        if (!clock.instant().isBefore(offer.expiresAt)) {
            throw WaitlistCommandException("OFFER_EXPIRED", offer.revision)
        }
        if (offer.revision != command.expectedRevision) {
            throw WaitlistCommandException("STALE_REVISION", offer.revision)
        }
        val entry = waitlists.findById(offer.entryId)
        if (entry.resourceId != offer.resourceId || entry.ownerDigest != offer.ownerDigest || entry.state != WaitlistState.OFFERED) {
            throw WaitlistCommandException("OFFER_ENTRY_MISMATCH", offer.revision)
        }
        if (!offers.transition(
                id = offer.id,
                ownerDigest = offer.ownerDigest,
                expectedRevision = command.expectedRevision,
                from = OfferState.ACTIVE,
                to = OfferState.ACCEPTED,
            )
        ) {
            throw WaitlistCommandException("STALE_REVISION", offer.revision)
        }
        if (!waitlists.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = entry.revision,
                from = WaitlistState.OFFERED,
                to = WaitlistState.ACCEPTED,
            )
        ) {
            throw WaitlistCommandException("STALE_WAITLIST_REVISION", entry.revision)
        }
        val acceptedHold = if (holds != null && resource != null) {
            holds.create(
                resourceId = resource.id,
                ownerDigest = offer.ownerDigest,
                policyVersion = resource.policyVersion,
                expiresAt = offer.expiresAt,
            ).also { created ->
                check(holds.transition(created.id, offer.ownerDigest, created.revision, HoldState.HELD, HoldState.CONFIRMED)) {
                    "accepted offer hold transition failed"
                }
            }.let { created -> holds.findById(created.id) }
        } else {
            null
        }
        val accepted = AcceptedOffer(offers.findById(offer.id), waitlists.findById(entry.id), acceptedHold)
        audits?.record("RESERVATION_OFFER", accepted.offer.id, accepted.offer.revision, accepted.offer.state.name)
        audits?.record("WAITLIST_ENTRY", accepted.entry.id, accepted.entry.revision, accepted.entry.state.name)
        accepted.hold?.also { confirmed ->
            audits?.record("RESERVATION_HOLD", confirmed.id, 0, HoldState.HELD.name)
            audits?.record("RESERVATION_HOLD", confirmed.id, confirmed.revision, confirmed.state.name)
        }
        log.debug {
            "reservation_offer_accept_applied offerId=${offer.id} entryId=${entry.id} resourceId=${offer.resourceId}"
        }
        return accepted
    }

    private fun requireOwner(rawOwner: String, expectedDigest: String, revision: Long) {
        if (!credentials.matchesOwner(rawOwner, expectedDigest)) {
            throw WaitlistCommandException("OWNER_MISMATCH", revision)
        }
    }

    companion object : KLogging()
}

internal data class JoinWaitlistCommand(
    val resourceId: Long,
    val ownerToken: String,
    val expectedResourceRevision: Long = 0,
    val policyVersion: Long = 1,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class CancelWaitlistCommand(
    val entryId: Long,
    val expectedRevision: Long,
    val ownerToken: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class AcceptOfferCommand(
    val offerId: Long,
    val expectedRevision: Long,
    val ownerToken: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class AcceptedOffer(
    val offer: ReservationOfferRecord,
    val entry: WaitlistEntryRecord,
    val hold: ReservationHoldRecord? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal class WaitlistCommandException(
    val reason: String,
    val currentRevision: Long,
) : RuntimeException(reason)
