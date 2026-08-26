package io.bluetape4k.workshop.optimization.shiftcoverage.config

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.shiftcoverage.ShiftCoverageApplication
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageDemoService
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageExecutorLifecycle
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageIdempotencyPort
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageIdempotencyStore
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageAssignmentStore
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(classes = [ShiftCoverageApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("demo")
class ShiftCoverageSpringWiringTest {
    @Autowired
    private lateinit var lifecycle: ShiftCoverageExecutorLifecycle

    @Autowired
    private lateinit var assignmentStore: ShiftCoverageAssignmentStore

    @Autowired
    private lateinit var idempotencyStore: ShiftCoverageIdempotencyPort

    @Autowired
    private lateinit var service: ShiftCoverageDemoService

    @Test
    fun `demo profile injects bounded planner and assignment idempotency ports`() {
        lifecycle.isReady().shouldBeTrue()
        assignmentStore.shouldBeInstanceOf<ShiftCoverageRepository>()
        idempotencyStore.shouldBeInstanceOf<ShiftCoverageIdempotencyStore>()
        service.replan().revision shouldBeEqualTo 1L
    }
}
