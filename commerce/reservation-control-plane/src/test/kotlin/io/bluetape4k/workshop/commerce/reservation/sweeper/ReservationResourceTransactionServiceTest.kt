package io.bluetape4k.workshop.commerce.reservation.sweeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.reservation.application.JoinWaitlistCommand
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCapacityHandoffService
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCredentialService
import io.bluetape4k.workshop.commerce.reservation.application.WaitlistCommandService
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationDeliveryRepository
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationDeliveryStatus
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditTable
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.reservationTables
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ReservationResourceTransactionServiceTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ReservationResourceTransactionServiceTest {

    companion object: KLogging()

/**
 * now은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-19T00:00:00Z")

    @Test
/**
 * 이 시나리오는 "expired hold promotes FIFO waiter and persists one durable notification on PostgreSQL"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
            resources.tryOccupy(resource.id, resource.revision).shouldBeTrue()

            val hold = holds.create(
                resource.id, credential.ownerDigest("holder-owner"), policyVersion = 1, expiresAt = now.minusSeconds(1)
            )
            val waitlistService = WaitlistCommandService(
                waitlists, offers, credential, Clock.fixed(now, ZoneOffset.UTC), 20.seconds(), resources, holds, audits
            )
            val entry = waitlistService.join(
                JoinWaitlistCommand(
                    resourceId = resource.id,
                    ownerToken = "waiter-owner",
                    expectedResourceRevision = 1,
                    policyVersion = 1
                )
            )
            val handoff = ReservationCapacityHandoffService(resources, waitlistService, notifications, audits)
            val service = ReservationResourceTransactionService(resources, holds, offers, waitlists, handoff, audits)

            val first = service.finalizeExpiredResource(resource.id, now)
            val second = service.finalizeExpiredResource(resource.id, now)

            first shouldBeEqualTo SweepBatchSummary(1, 1, 1, 0)
            second shouldBeEqualTo SweepBatchSummary(1, 0, 0, 0)

            holds.findById(hold.id).state shouldBeEqualTo HoldState.EXPIRED
            waitlists.findById(entry.id).state shouldBeEqualTo WaitlistState.OFFERED
            resources.findById(resource.id).occupiedCount shouldBeEqualTo 1

            val offer = offers.snapshots(resource.id).single()
            notifications.findByDeliveryId("offer-created:${offer.id}:0:in-app")?.delivery?.status shouldBeEqualTo NotificationDeliveryStatus.PENDING
            ReservationAuditTable.selectAll().count() shouldBeEqualTo 4L
        }
    }

    @Test
/**
 * 이 시나리오는 "expired offer is finalized and the next FIFO waiter receives the occupied capacity" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

            resources.tryOccupy(resource.id, resource.revision).shouldBeTrue()

            val waitlistService = WaitlistCommandService(
                waitlists,
                offers,
                credential,
                Clock.fixed(now - 30.seconds(), ZoneOffset.UTC),
                20.seconds(),
                resources,
                holds,
                audits
            )

            val firstEntry = waitlists.join(resource.id, credential.ownerDigest("first-waiter"))
            val firstOffer = checkNotNull(waitlistService.promote(resource.id))
            val secondEntry = waitlists.join(resource.id, credential.ownerDigest("second-waiter"))
            val handoff = ReservationCapacityHandoffService(resources, waitlistService, notifications, audits)
            val service = ReservationResourceTransactionService(resources, holds, offers, waitlists, handoff, audits)

            service.expiredResourceIds(now, 32) shouldBeEqualTo listOf(resource.id)

            val summary = service.finalizeExpiredResource(resource.id, now)

            summary shouldBeEqualTo SweepBatchSummary(1, 0, 1, 0)
            offers.findById(firstOffer.id).state shouldBeEqualTo OfferState.EXPIRED
            waitlists.findById(firstEntry.id).state shouldBeEqualTo WaitlistState.EXPIRED
            waitlists.findById(secondEntry.id).state shouldBeEqualTo WaitlistState.OFFERED
            resources.findById(resource.id).occupiedCount shouldBeEqualTo 1

            offers.snapshots(resource.id).map { it.state } shouldBeEqualTo listOf(OfferState.EXPIRED, OfferState.ACTIVE)
        }
    }

    @Test
/**
 * 이 시나리오는 "expired offer releases capacity when no waiter remains" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

            resources.tryOccupy(resource.id, resource.revision).shouldBeTrue()

            val waitlistService = WaitlistCommandService(
                waitlists,
                offers,
                credential,
                Clock.fixed(now - 30.seconds(), ZoneOffset.UTC),
                20.seconds(),
                resources,
                holds,
                audits
            )
            val entry = waitlists.join(resource.id, credential.ownerDigest("only-waiter"))
            val offer = checkNotNull(waitlistService.promote(resource.id))
            val handoff = ReservationCapacityHandoffService(resources, waitlistService, notifications, audits)
            val service = ReservationResourceTransactionService(resources, holds, offers, waitlists, handoff, audits)

            val summary = service.finalizeExpiredResource(resource.id, now)

            summary shouldBeEqualTo SweepBatchSummary(1, 0, 0, 0)
            offers.findById(offer.id).state shouldBeEqualTo OfferState.EXPIRED
            waitlists.findById(entry.id).state shouldBeEqualTo WaitlistState.EXPIRED
            resources.findById(resource.id).occupiedCount shouldBeEqualTo 0
            resources.findById(resource.id).revision shouldBeEqualTo 2
        }
    }

    @Test
/**
 * 이 시나리오는 "mixed expiry candidates are selected globally by oldest expiry instead of aggregate
 * type" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
                Clock.fixed(now - 60.seconds(), ZoneOffset.UTC),
                20.seconds(),
                resources,
                holds,
                audits
            )

            repeat(32) { index ->
                val resource = resources.create("newer-expired-hold-$index", capacity = 1, policyVersion = 1)
                resources.tryOccupy(resource.id, resource.revision).shouldBeTrue()

                holds.create(
                    resourceId = resource.id,
                    ownerDigest = credential.ownerDigest("holder-$index"),
                    policyVersion = 1,
                    expiresAt = now - 1.seconds()
                )
            }
            val offerResource = resources.create("oldest-expired-offer", capacity = 1, policyVersion = 1)

            resources.tryOccupy(offerResource.id, offerResource.revision).shouldBeTrue()
            waitlists.join(offerResource.id, credential.ownerDigest("offer-waiter"))
            waitlistService.promote(offerResource.id).shouldNotBeNull()

            val handoff = ReservationCapacityHandoffService(resources, waitlistService, notifications, audits)
            val service = ReservationResourceTransactionService(resources, holds, offers, waitlists, handoff, audits)

            val candidates = service.expiredResourceIds(now, 32)
            candidates shouldHaveSize 32
            candidates.first() shouldBeEqualTo offerResource.id
        }
    }
}
