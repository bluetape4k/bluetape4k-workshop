package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
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

            offer?.entryId shouldBeEqualTo first.id
            offer?.state shouldBeEqualTo OfferState.ACTIVE
            offer?.expiresAt shouldBeEqualTo now.plusSeconds(20)
            waitlists.findById(first.id).state shouldBeEqualTo WaitlistState.OFFERED
            waitlists.findById(second.id).state shouldBeEqualTo WaitlistState.WAITING
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

            assertFailsWith<WaitlistCommandException> {
                service.accept(AcceptOfferCommand(offer.id, expectedRevision = 0, ownerToken = "other-owner"))
            }
            assertFailsWith<WaitlistCommandException> {
                service.accept(AcceptOfferCommand(offer.id, expectedRevision = 9, ownerToken = "offer-owner"))
            }

            val accepted =
                service.accept(
                    AcceptOfferCommand(offer.id, expectedRevision = 0, ownerToken = "offer-owner")
                )

            accepted.offer.state shouldBeEqualTo OfferState.ACCEPTED
            accepted.offer.revision shouldBeEqualTo 1L
            accepted.entry.state shouldBeEqualTo WaitlistState.ACCEPTED
            accepted.entry.revision shouldBeEqualTo 2L
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
            val acceptService =
                WaitlistCommandService(waitlists, offers, credentialService, expiredClock, Duration.ofSeconds(20))

            val error = assertFailsWith<WaitlistCommandException> {
                acceptService.accept(AcceptOfferCommand(offer.id, expectedRevision = 0, ownerToken = "offer-owner"))
            }

            error.reason shouldBeEqualTo "OFFER_EXPIRED"
            offers.findById(offer.id).state shouldBeEqualTo OfferState.ACTIVE
            waitlists.findById(entry.id).state shouldBeEqualTo WaitlistState.OFFERED
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
        offerTtl = Duration.ofSeconds(20)
    )
}
