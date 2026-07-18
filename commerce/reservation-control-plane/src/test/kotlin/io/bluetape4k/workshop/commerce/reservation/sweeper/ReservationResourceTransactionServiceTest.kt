package io.bluetape4k.workshop.commerce.reservation.sweeper

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.workshop.commerce.reservation.application.JoinWaitlistCommand
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCredentialService
import io.bluetape4k.workshop.commerce.reservation.application.WaitlistCommandService
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCapacityHandoffService
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationDeliveryRepository
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationDeliveryTable
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationDeliveryStatus
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceTable
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldTable
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferTable
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditTable
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.reservationTables
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

internal class ReservationResourceTransactionServiceTest {
    private val now = Instant.parse("2026-07-19T00:00:00Z")

    @Test
    fun `expired hold promotes FIFO waiter and persists one durable notification on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resources = CapacityResourceRepository()
            val holds = ReservationHoldRepository()
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val notifications = NotificationDeliveryRepository()
            val audits = ReservationAuditRepository()
            val credential = ReservationCredentialService("0123456789abcdef0123456789abcdef")
            val resource = resources.create("sweep-room", capacity = 1, policyVersion = 1)
            check(resources.tryOccupy(resource.id, resource.revision))
            val hold = holds.create(
                resource.id,
                credential.ownerDigest("holder-owner"),
                policyVersion = 1,
                expiresAt = now.minusSeconds(1),
            )
            val waitlistService = WaitlistCommandService(
                waitlists,
                offers,
                credential,
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(20),
                resources,
                holds,
                audits,
            )
            val entry = waitlistService.join(
                JoinWaitlistCommand(resource.id, "waiter-owner", expectedResourceRevision = 1, policyVersion = 1),
            )
            val handoff = ReservationCapacityHandoffService(resources, waitlistService, notifications, audits)
            val service = ReservationResourceTransactionService(resources, holds, offers, waitlists, handoff, audits)

            val first = service.finalizeExpiredResource(resource.id, now)
            val second = service.finalizeExpiredResource(resource.id, now)

            assertEquals(SweepBatchSummary(1, 1, 1, 0), first)
            assertEquals(SweepBatchSummary(1, 0, 0, 0), second)
            assertEquals(HoldState.EXPIRED, holds.findById(hold.id).state)
            assertEquals(WaitlistState.OFFERED, waitlists.findById(entry.id).state)
            assertEquals(1, resources.findById(resource.id).occupiedCount)
            val offer = offers.snapshots(resource.id).single()
            assertEquals(
                NotificationDeliveryStatus.PENDING,
                notifications.findByDeliveryId("offer-created:${offer.id}:0:in-app")?.delivery?.status,
            )
            assertEquals(4L, ReservationAuditTable.selectAll().count())
        }
    }

    @Test
    fun `expired offer is finalized and the next FIFO waiter receives the occupied capacity`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resources = CapacityResourceRepository()
            val holds = ReservationHoldRepository()
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val notifications = NotificationDeliveryRepository()
            val audits = ReservationAuditRepository()
            val credential = ReservationCredentialService("0123456789abcdef0123456789abcdef")
            val resource = resources.create("expired-offer-room", capacity = 1, policyVersion = 1)
            check(resources.tryOccupy(resource.id, resource.revision))
            val waitlistService = WaitlistCommandService(
                waitlists,
                offers,
                credential,
                Clock.fixed(now.minusSeconds(30), ZoneOffset.UTC),
                Duration.ofSeconds(20),
                resources,
                holds,
                audits,
            )
            val firstEntry = waitlists.join(resource.id, credential.ownerDigest("first-waiter"))
            val firstOffer = checkNotNull(waitlistService.promote(resource.id))
            val secondEntry = waitlists.join(resource.id, credential.ownerDigest("second-waiter"))
            val handoff = ReservationCapacityHandoffService(resources, waitlistService, notifications, audits)
            val service = ReservationResourceTransactionService(resources, holds, offers, waitlists, handoff, audits)

            assertEquals(listOf(resource.id), service.expiredResourceIds(now, 32))
            val summary = service.finalizeExpiredResource(resource.id, now)

            assertEquals(SweepBatchSummary(1, 0, 1, 0), summary)
            assertEquals(OfferState.EXPIRED, offers.findById(firstOffer.id).state)
            assertEquals(WaitlistState.EXPIRED, waitlists.findById(firstEntry.id).state)
            assertEquals(WaitlistState.OFFERED, waitlists.findById(secondEntry.id).state)
            assertEquals(1, resources.findById(resource.id).occupiedCount)
            assertEquals(listOf(OfferState.EXPIRED, OfferState.ACTIVE), offers.snapshots(resource.id).map { it.state })
        }
    }

    @Test
    fun `expired offer releases capacity when no waiter remains`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resources = CapacityResourceRepository()
            val holds = ReservationHoldRepository()
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val notifications = NotificationDeliveryRepository()
            val audits = ReservationAuditRepository()
            val credential = ReservationCredentialService("0123456789abcdef0123456789abcdef")
            val resource = resources.create("expired-offer-release-room", capacity = 1, policyVersion = 1)
            check(resources.tryOccupy(resource.id, resource.revision))
            val waitlistService = WaitlistCommandService(
                waitlists,
                offers,
                credential,
                Clock.fixed(now.minusSeconds(30), ZoneOffset.UTC),
                Duration.ofSeconds(20),
                resources,
                holds,
                audits,
            )
            val entry = waitlists.join(resource.id, credential.ownerDigest("only-waiter"))
            val offer = checkNotNull(waitlistService.promote(resource.id))
            val handoff = ReservationCapacityHandoffService(resources, waitlistService, notifications, audits)
            val service = ReservationResourceTransactionService(resources, holds, offers, waitlists, handoff, audits)

            val summary = service.finalizeExpiredResource(resource.id, now)

            assertEquals(SweepBatchSummary(1, 0, 0, 0), summary)
            assertEquals(OfferState.EXPIRED, offers.findById(offer.id).state)
            assertEquals(WaitlistState.EXPIRED, waitlists.findById(entry.id).state)
            assertEquals(0, resources.findById(resource.id).occupiedCount)
            assertEquals(2, resources.findById(resource.id).revision)
        }
    }

    @Test
    fun `mixed expiry candidates are selected globally by oldest expiry instead of aggregate type`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resources = CapacityResourceRepository()
            val holds = ReservationHoldRepository()
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val notifications = NotificationDeliveryRepository()
            val audits = ReservationAuditRepository()
            val credential = ReservationCredentialService("0123456789abcdef0123456789abcdef")
            val waitlistService = WaitlistCommandService(
                waitlists,
                offers,
                credential,
                Clock.fixed(now.minusSeconds(60), ZoneOffset.UTC),
                Duration.ofSeconds(20),
                resources,
                holds,
                audits,
            )
            repeat(32) { index ->
                val resource = resources.create("newer-expired-hold-$index", capacity = 1, policyVersion = 1)
                check(resources.tryOccupy(resource.id, resource.revision))
                holds.create(
                    resource.id,
                    credential.ownerDigest("holder-$index"),
                    policyVersion = 1,
                    expiresAt = now.minusSeconds(1),
                )
            }
            val offerResource = resources.create("oldest-expired-offer", capacity = 1, policyVersion = 1)
            check(resources.tryOccupy(offerResource.id, offerResource.revision))
            waitlists.join(offerResource.id, credential.ownerDigest("offer-waiter"))
            checkNotNull(waitlistService.promote(offerResource.id))
            val handoff = ReservationCapacityHandoffService(resources, waitlistService, notifications, audits)
            val service = ReservationResourceTransactionService(resources, holds, offers, waitlists, handoff, audits)

            val candidates = service.expiredResourceIds(now, 32)

            assertEquals(32, candidates.size)
            assertEquals(offerResource.id, candidates.first())
        }
    }
}
