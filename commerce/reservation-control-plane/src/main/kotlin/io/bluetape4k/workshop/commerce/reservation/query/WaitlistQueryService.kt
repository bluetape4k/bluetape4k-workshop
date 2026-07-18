package io.bluetape4k.workshop.commerce.reservation.query

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCredentialService
import io.bluetape4k.workshop.commerce.reservation.application.WaitlistCommandException
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

/** Returns owner-scoped waitlist position and active-offer state without exposing stored credential digests. */
@Service
internal class WaitlistQueryService(
    private val waitlists: WaitlistEntryRepository,
    private val offers: ReservationOfferRepository,
    private val credentials: ReservationCredentialService,
) {
    @Transactional(readOnly = true)
    fun entry(entryId: Long, rawOwner: String): WaitlistSnapshot {
        val entry = waitlists.findById(entryId)
        requireOwner(rawOwner, entry.ownerDigest, entry.revision)
        val position = waitlists.snapshots(entry.resourceId)
            .filter { it.state.name == "WAITING" || it.id == entry.id }
            .indexOfFirst { it.id == entry.id }
            .let { if (it < 0) 0 else it + 1 }
        log.debug { "waitlist_snapshot_queried entryId=$entryId position=$position" }
        return WaitlistSnapshot(entry, position, offers.activeForEntry(entry.id))
    }

    @Transactional(readOnly = true)
    fun offer(offerId: Long, rawOwner: String): ReservationOfferRecord {
        val offer = offers.findById(offerId)
        requireOwner(rawOwner, offer.ownerDigest, offer.revision)
        log.debug { "reservation_offer_snapshot_queried offerId=$offerId" }
        return offer
    }

    private fun requireOwner(rawOwner: String, expectedDigest: String, revision: Long) {
        if (!credentials.matchesOwner(rawOwner, expectedDigest)) {
            throw WaitlistCommandException("OWNER_MISMATCH", revision)
        }
    }

    companion object : KLogging()
}

internal data class WaitlistSnapshot(
    val entry: WaitlistEntryRecord,
    val position: Int,
    val activeOffer: ReservationOfferRecord?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
