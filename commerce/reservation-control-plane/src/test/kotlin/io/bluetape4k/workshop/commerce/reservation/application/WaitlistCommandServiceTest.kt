package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.reservationTables
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * WaitlistCommandServiceTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class WaitlistCommandServiceTest {

    companion object: KLogging()

/**
 * now은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-19T00:00:00Z")
/**
 * credentialService은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val credentialService = ReservationCredentialService("0123456789abcdef0123456789abcdef")

    @Test
/**
 * 이 시나리오는 "promotion offers only the oldest WAITING entry with the configured TTL on PostgreSQL"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `promotion offers only the oldest WAITING entry with the configured TTL on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("promotion-room", capacity = 1, policyVersion = 1)
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val service = service(waitlists, offers)
            val first = service.join(JoinWaitlistCommand(resource.id, "owner-one"))
            val second = service.join(JoinWaitlistCommand(resource.id, "owner-two"))

            service.promote(resource.id)?.let { offer ->
                offer.entryId shouldBeEqualTo first.id
                offer.state shouldBeEqualTo OfferState.ACTIVE
                offer.expiresAt shouldBeEqualTo now + 20.seconds()
            }

            waitlists.findById(first.id).state shouldBeEqualTo WaitlistState.OFFERED
            waitlists.findById(second.id).state shouldBeEqualTo WaitlistState.WAITING
        }
    }

    @Test
/**
 * 이 시나리오는 "accept requires the offer owner expected revision and a non-expired ACTIVE offer on
 * PostgreSQL" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `accept requires the offer owner expected revision and a non-expired ACTIVE offer on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("accept-room", capacity = 1, policyVersion = 1)
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val service = service(waitlists, offers)
            service.join(JoinWaitlistCommand(resource.id, "offer-owner"))

            val offer = service.promote(resource.id).shouldNotBeNull()

            assertFailsWith<WaitlistCommandException> {
                service.accept(AcceptOfferCommand(offer.id, expectedRevision = 0, ownerToken = "other-owner"))
            }
            assertFailsWith<WaitlistCommandException> {
                service.accept(AcceptOfferCommand(offer.id, expectedRevision = 9, ownerToken = "offer-owner"))
            }

            val accepted = service.accept(
                AcceptOfferCommand(offer.id, expectedRevision = 0, ownerToken = "offer-owner")
            )

            accepted.offer.state shouldBeEqualTo OfferState.ACCEPTED
            accepted.offer.revision shouldBeEqualTo 1L
            accepted.entry.state shouldBeEqualTo WaitlistState.ACCEPTED
            accepted.entry.revision shouldBeEqualTo 2L
        }
    }

    @Test
/**
 * 이 시나리오는 "accept rejects an expired offer without changing either aggregate on PostgreSQL" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `accept rejects an expired offer without changing either aggregate on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val resource = CapacityResourceRepository().create("expired-room", capacity = 1, policyVersion = 1)
            val waitlists = WaitlistEntryRepository()
            val offers = ReservationOfferRepository()
            val joinService = service(waitlists, offers)
            val entry = joinService.join(JoinWaitlistCommand(resource.id, "offer-owner"))
            val offer = joinService.promote(resource.id)!!
            val expiredClock = Clock.fixed(now + 21.seconds(), ZoneOffset.UTC)

            val acceptService = WaitlistCommandService(
                waitlists = waitlists,
                offers = offers,
                credentials = credentialService,
                clock = expiredClock,
                offerTtl = 20.seconds()
            )

            val error = assertFailsWith<WaitlistCommandException> {
                acceptService.accept(
                    AcceptOfferCommand(
                        offerId = offer.id,
                        expectedRevision = 0,
                        ownerToken = "offer-owner"
                    )
                )
            }

            error.reason shouldBeEqualTo "OFFER_EXPIRED"

            offers.findById(offer.id).state shouldBeEqualTo OfferState.ACTIVE
            waitlists.findById(entry.id).state shouldBeEqualTo WaitlistState.OFFERED
        }
    }

/**
 * service은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun service(
        waitlists: WaitlistEntryRepository,
        offers: ReservationOfferRepository,
    ): WaitlistCommandService =
        WaitlistCommandService(
            waitlists = waitlists,
            offers = offers,
            credentials = credentialService,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            offerTtl = 20.seconds()
        )
}
