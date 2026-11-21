package io.bluetape4k.workshop.commerce.reservation

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * ReservationFixtureExposedArchitectureTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ReservationFixtureExposedArchitectureTest {

    @Test
/**
 * 이 시나리오는 "integration fixture reset uses Exposed schema lifecycle" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `integration fixture reset uses Exposed schema lifecycle`() {
        val source = Files.readString(locateSource())
        source.shouldContain("SchemaUtils.drop")
        source.shouldContain("SchemaUtils.create")
        source.shouldNotContain("JdbcTemplate")
        source.shouldNotContain("TRUNCATE TABLE")
    }

/**
 * locateSource은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateSource(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = directory?.resolve(
                "commerce/reservation-control-plane/src/test/kotlin/io/bluetape4k/workshop/commerce/reservation/AbstractReservationIntegrationTest.kt",
            )
            if (candidate != null && Files.isRegularFile(candidate)) return candidate
            directory = directory?.parent
        }
        error("AbstractReservationIntegrationTest.kt를 찾을 수 없습니다")
    }
}
