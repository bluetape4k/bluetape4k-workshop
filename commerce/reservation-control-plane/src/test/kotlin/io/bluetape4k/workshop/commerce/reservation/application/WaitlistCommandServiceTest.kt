package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceTable
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferTable
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryTable
import io.bluetape4k.workshop.commerce.reservation.persistence.reservationTables
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class WaitlistCommandServiceTest {
    private val now = Instant.parse("2026-07-19T00:00:00Z")
    private val credentialService = ReservationCredentialService("0123456789abcdef0123456789abcdef")

    @Test
    fun `promotion offers only the oldest WAITING entry with the configured TTL on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("promotion-room", capacity = 1, policyVersion = 1)
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val service = service(waitlists, offers)
            val first = service.join(JoinWaitlistCommand(resource.id, "owner-one"))
            val second = service.join(JoinWaitlistCommand(resource.id, "owner-two"))

            val offer = service.promote(resource.id)

            assertEquals(first.id, offer?.entryId)
            assertEquals(OfferState.ACTIVE, offer?.state)
            assertEquals(now.plusSeconds(20), offer?.expiresAt)
            assertEquals(WaitlistState.OFFERED, waitlists.findById(first.id).state)
            assertEquals(WaitlistState.WAITING, waitlists.findById(second.id).state)
        }
    }

    @Test
    fun `accept requires the offer owner expected revision and a non-expired ACTIVE offer on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("accept-room", capacity = 1, policyVersion = 1)
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val service = service(waitlists, offers)
            service.join(JoinWaitlistCommand(resource.id, "offer-owner"))
            val offer = service.promote(resource.id)!!

            assertThrows(WaitlistCommandException::class.java) {
                service.accept(AcceptOfferCommand(offer.id, expectedRevision = 0, ownerToken = "other-owner"))
            }
            assertThrows(WaitlistCommandException::class.java) {
                service.accept(AcceptOfferCommand(offer.id, expectedRevision = 9, ownerToken = "offer-owner"))
            }

            val accepted = service.accept(AcceptOfferCommand(offer.id, expectedRevision = 0, ownerToken = "offer-owner"))

            assertEquals(OfferState.ACCEPTED, accepted.offer.state)
            assertEquals(1L, accepted.offer.revision)
            assertEquals(WaitlistState.ACCEPTED, accepted.entry.state)
            assertEquals(2L, accepted.entry.revision)
        }
    }

    @Test
    fun `accept rejects an expired offer without changing either aggregate on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("expired-room", capacity = 1, policyVersion = 1)
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val joinService = service(waitlists, offers)
            val entry = joinService.join(JoinWaitlistCommand(resource.id, "offer-owner"))
            val offer = joinService.promote(resource.id)!!
            val expiredClock = Clock.fixed(now.plusSeconds(21), ZoneOffset.UTC)
            val acceptService = WaitlistCommandService(waitlists, offers, credentialService, expiredClock, Duration.ofSeconds(20))

            val error = assertThrows(WaitlistCommandException::class.java) {
                acceptService.accept(AcceptOfferCommand(offer.id, expectedRevision = 0, ownerToken = "offer-owner"))
            }

            assertEquals("OFFER_EXPIRED", error.reason)
            assertEquals(OfferState.ACTIVE, offers.findById(offer.id).state)
            assertEquals(WaitlistState.OFFERED, waitlists.findById(entry.id).state)
        }
    }

    private fun service(
        waitlists: WaitlistEntryRepository,
        offers: ReservationOfferRepository,
    ) = WaitlistCommandService(
        waitlists = waitlists,
        offers = offers,
        credentials = credentialService,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        offerTtl = Duration.ofSeconds(20),
    )
}
