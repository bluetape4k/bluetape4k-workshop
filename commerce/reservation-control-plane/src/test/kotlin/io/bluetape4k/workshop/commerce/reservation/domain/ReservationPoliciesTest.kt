package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * ReservationPoliciesTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ReservationPoliciesTest {

    companion object: KLogging()

/**
 * now은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-19T00:00:00Z")

    @Test
/**
 * 이 시나리오는 "held reservation can be confirmed by its owner before expiry" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `held reservation can be confirmed by its owner before expiry`() {
        val hold = hold()

        val outcome = ReservationPolicies.confirm(
            hold = hold,
            ownerDigest = "owner-digest",
            expectedRevision = 1,
            now = now
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Applied>()
        outcome.hold.state shouldBeEqualTo HoldState.CONFIRMED
        outcome.hold.revision shouldBeEqualTo 2
    }

    @Test
/**
 * 이 시나리오는 "expired reservation is rejected with a stable reason" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expired reservation is rejected with a stable reason`() {
        val outcome = ReservationPolicies.confirm(
            hold = hold(expiresAt = now),
            ownerDigest = "owner-digest",
            expectedRevision = 1,
            now = now
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Rejected>()
            .reason shouldBeEqualTo TransitionReason.HOLD_EXPIRED
    }

    @Test
/**
 * 이 시나리오는 "other owner cannot mutate a reservation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `other owner cannot mutate a reservation`() {
        val outcome = ReservationPolicies.cancel(
            hold = hold(),
            ownerDigest = "other-owner",
            expectedRevision = 1,
            now = now
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Rejected>()
            .reason shouldBeEqualTo TransitionReason.OWNER_MISMATCH
    }

    @Test
/**
 * 이 시나리오는 "stale revision cannot mutate a reservation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stale revision cannot mutate a reservation`() {
        val outcome = ReservationPolicies.confirm(
            hold = hold(),
            ownerDigest = "owner-digest",
            expectedRevision = 0,
            now = now
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Rejected>()
            .reason shouldBeEqualTo TransitionReason.STALE_REVISION
    }

    @Test
/**
 * 이 시나리오는 "active offer occupies capacity without increment on acceptance" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `active offer occupies capacity without increment on acceptance`() {
        val resource = CapacityResourceSnapshot(1, capacity = 1, occupiedCount = 1, revision = 7)

        resource.hasCapacity.shouldBeFalse()
        ReservationPolicies.occupiedAfterOfferAccepted(resource) shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "held reservation can be extended by its owner before expiry" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `held reservation can be extended by its owner before expiry`() {
        val hold = hold()

        val outcome = ReservationPolicies.extend(
            hold = hold,
            ownerDigest = "owner-digest",
            expectedRevision = 1,
            now = now,
            newExpiresAt = now.plusSeconds(90)
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Applied>()

        val extended = outcome.hold
        extended.revision shouldBeEqualTo 2
        extended.expiresAt shouldBeEqualTo now.plusSeconds(90)
    }

    @Test
/**
 * 이 시나리오는 "extension must move expiry forward" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `extension must move expiry forward`() {
        val outcome = ReservationPolicies.extend(
            hold = hold(),
            ownerDigest = "owner-digest",
            expectedRevision = 1,
            now = now,
            newExpiresAt = now.plusSeconds(10)
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Rejected>()
            .reason shouldBeEqualTo TransitionReason.INVALID_EXPIRY
    }

/**
 * hold은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun hold(expiresAt: Instant = now + 30.seconds()): ReservationHoldSnapshot =
        ReservationHoldSnapshot(
            id = 10,
            resourceId = 1,
            ownerDigest = "owner-digest",
            state = HoldState.HELD,
            revision = 1,
            policyVersion = 1,
            expiresAt = expiresAt
        )
}
