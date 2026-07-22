package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.ACTIVE
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.BUILDING
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.FAILED
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState.RETIRED
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectionGenerationTest {
    @Test
    fun `generation state machine exposes switch failure retirement and rollback only`() {
        assertTrue(ProjectionGenerationTransitions.allows(BUILDING, ACTIVE))
        assertTrue(ProjectionGenerationTransitions.allows(BUILDING, FAILED))
        assertTrue(ProjectionGenerationTransitions.allows(ACTIVE, RETIRED))
        assertTrue(ProjectionGenerationTransitions.allows(RETIRED, ACTIVE))

        assertFalse(ProjectionGenerationTransitions.allows(ACTIVE, BUILDING))
        assertFalse(ProjectionGenerationTransitions.allows(FAILED, ACTIVE))
    }
}
