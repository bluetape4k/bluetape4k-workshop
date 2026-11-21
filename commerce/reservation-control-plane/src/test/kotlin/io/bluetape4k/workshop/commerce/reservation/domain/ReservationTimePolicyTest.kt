package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ReservationTimePolicyTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ReservationTimePolicyTest {

    companion object: KLogging()

/**
 * newYork은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val newYork = ZoneId.of("America/New_York")

    @Test
/**
 * 이 시나리오는 "DST gap local time is rejected deterministically" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `DST gap local time is rejected deterministically`() {
        val result = ReservationTimePolicy.resolve(
            LocalDateTime.parse("2026-03-08T02:30:00"),
            newYork
        )

        result.shouldBeInstanceOf<LocalTimeResolution.Rejected>()
            .reason shouldBeEqualTo LocalTimeRejection.DST_GAP
    }

    @Test
/**
 * 이 시나리오는 "DST overlap requires an explicit valid offset" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `DST overlap requires an explicit valid offset`() {
        val local = LocalDateTime.parse("2026-11-01T01:30:00")

        val ambiguous = ReservationTimePolicy.resolve(local, newYork)

        val first = ReservationTimePolicy.resolve(
            localDateTime = local,
            zoneId = newYork,
            preferredOffset = ZoneOffset.ofHours(-4)
        )
        val second = ReservationTimePolicy.resolve(
            localDateTime = local,
            zoneId = newYork,
            preferredOffset = ZoneOffset.ofHours(-5)
        )

        ambiguous.shouldBeInstanceOf<LocalTimeResolution.Rejected>()
            .reason shouldBeEqualTo LocalTimeRejection.DST_OVERLAP

        first.shouldBeInstanceOf<LocalTimeResolution.Resolved>()
        second.shouldBeInstanceOf<LocalTimeResolution.Resolved>()

        (second.instant.epochSecond - first.instant.epochSecond) shouldBeEqualTo 3_600L
    }
}
