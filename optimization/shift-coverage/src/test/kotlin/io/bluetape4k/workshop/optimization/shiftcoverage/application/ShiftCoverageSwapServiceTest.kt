package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.IdempotencyKey
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageRepository
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ShiftCoverageSwapServiceTest {

    companion object: KLogging()

    @Test
    fun `only one concurrent acceptance wins expected revision CAS`() {
        val repository = ShiftCoverageRepository()
        val service = ShiftCoverageSwapService(repository)
        val assignment = ShiftAssignment(
            AssignmentId("assignment-a"),
            SiteId("site-a"),
            ShiftId("shift-a"),
            WorkerId("worker-a")
        )

        repository.saveAssignment(assignment)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val results = List(2) { index ->
            pool.submit<Boolean> {
                ready.countDown(); start.await()
                service.accept(
                    ShiftSwapAcceptance(
                        assignment.assignmentId, WorkerId("worker-${index + 1}"), expectedRevision = 0L,
                        expectedPlanRevision = 0L, idempotencyKey = IdempotencyKey("key-${index + 1}"),
                    ),
                )
            }
        }
        ready.await()
        start.countDown()

        val winners = results.count { it.get() }
        pool.shutdownNow()

        winners shouldBeEqualTo 1
        repository.findAssignment(assignment.assignmentId)?.revision shouldBeEqualTo 1L
        service.accept(
            ShiftSwapAcceptance(
                assignment.assignmentId,
                WorkerId("worker-z"),
                0L,
                0L,
                IdempotencyKey("key-z")
            )
        ).shouldBeFalse()
    }
}
