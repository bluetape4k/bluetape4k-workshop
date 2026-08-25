package io.bluetape4k.workshop.optimization.clinicappointment.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.clinicappointment.fixture.ClinicAppointmentFixtures
import org.junit.jupiter.api.Test

class ClinicAppointmentPlanningTest {

    @Test
    fun `confirmed appointment remains pinned and keeps its initial assignment`() {
        val appointment = ClinicAppointmentFixtures.snapshot().appointments
            .first { it.confirmed }

        appointment.pinned.shouldBeTrue()
        appointment.providerId shouldBeEqualTo "provider-2"
        appointment.roomId shouldBeEqualTo "room-2"
    }

    @Test
    fun `fixture exposes stable sorted ids and value ranges`() {
        val snapshot = ClinicAppointmentFixtures.snapshot()

        snapshot.providers.map { it.id } shouldBeEqualTo listOf("provider-1", "provider-2", "provider-3")
        snapshot.rooms.map { it.id } shouldBeEqualTo listOf("room-1", "room-2")
        snapshot.appointments.map { it.requestId } shouldBeEqualTo listOf("appointment-1", "appointment-2", "appointment-3")
        snapshot.dates shouldBeEqualTo snapshot.dates.sorted()
        snapshot.timeSlots shouldBeEqualTo snapshot.timeSlots.sorted()
    }
}
