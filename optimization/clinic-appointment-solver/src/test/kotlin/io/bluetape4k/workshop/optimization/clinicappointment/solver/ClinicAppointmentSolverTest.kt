package io.bluetape4k.workshop.optimization.clinicappointment.solver

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.clinicappointment.fixture.ClinicAppointmentFixtures
import org.junit.jupiter.api.Test

class ClinicAppointmentSolverTest {
    private val solver = ClinicAppointmentSolver()

    @Test
    fun `same 기준 data converges to the same sorted proposal`() {
        val first = solver.solve(ClinicAppointmentFixtures.snapshot())
        val second = solver.solve(ClinicAppointmentFixtures.snapshot())

        first shouldBeEqualTo second
        first.assignments.map { it.requestId } shouldBeEqualTo
            first.assignments.map { it.requestId }.sorted()
        first.hardScore shouldBeEqualTo second.hardScore
        first.softScore shouldBeEqualTo second.softScore
    }

    @Test
    fun `confirmed appointment stays pinned and input remains unchanged`() {
        val input = ClinicAppointmentFixtures.snapshot()
        val proposal = solver.solve(input)
        val pinned = proposal.assignments.first { it.requestId == "appointment-3" }

        pinned.providerId shouldBeEqualTo "provider-2"
        pinned.roomId shouldBeEqualTo "room-2"
        pinned.date shouldBeEqualTo input.dates.first()
        pinned.startTime?.toString().shouldBeEqualTo("10:00")
        input.appointments.first { it.requestId == "appointment-1" }.providerId.shouldBeEqualTo(null)
        proposal.feasible.shouldBeTrue()
    }

    @Test
    fun `empty input is rejected before building a solver`() {
        val empty = ClinicAppointmentFixtures.snapshot().copy(appointments = emptyList())

        assertFailsWith<IllegalArgumentException> { solver.solve(empty) }
    }
}
