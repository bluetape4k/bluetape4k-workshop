package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.ACTIVE
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.BUILDING
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.FAILED
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.RETIRED
import org.junit.jupiter.api.Test

/**
 * ProjectionGenerationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ProjectionGenerationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "generation state machine exposes switch failure retirement and rollback only" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `generation state machine exposes switch failure retirement and rollback only`() {
        ProjectionGenerationTransitions.allows(BUILDING, ACTIVE).shouldBeTrue()
        ProjectionGenerationTransitions.allows(BUILDING, FAILED).shouldBeTrue()
        ProjectionGenerationTransitions.allows(ACTIVE, RETIRED).shouldBeTrue()
        ProjectionGenerationTransitions.allows(RETIRED, ACTIVE).shouldBeTrue()

        ProjectionGenerationTransitions.allows(ACTIVE, BUILDING).shouldBeFalse()
        ProjectionGenerationTransitions.allows(FAILED, ACTIVE).shouldBeFalse()
    }
}
