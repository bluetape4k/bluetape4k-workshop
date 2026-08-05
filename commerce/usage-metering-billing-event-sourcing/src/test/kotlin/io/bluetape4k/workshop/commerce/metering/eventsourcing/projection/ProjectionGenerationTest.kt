package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.ACTIVE
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.BUILDING
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.FAILED
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.RETIRED
import org.junit.jupiter.api.Test

class ProjectionGenerationTest {
    @Test
    fun `generation state machine exposes switch failure retirement and rollback only`() {
        ProjectionGenerationTransitions.allows(BUILDING, ACTIVE).shouldBeTrue()
        ProjectionGenerationTransitions.allows(BUILDING, FAILED).shouldBeTrue()
        ProjectionGenerationTransitions.allows(ACTIVE, RETIRED).shouldBeTrue()
        ProjectionGenerationTransitions.allows(RETIRED, ACTIVE).shouldBeTrue()

        ProjectionGenerationTransitions.allows(ACTIVE, BUILDING).shouldBeFalse()
        ProjectionGenerationTransitions.allows(FAILED, ACTIVE).shouldBeFalse()
    }
}
