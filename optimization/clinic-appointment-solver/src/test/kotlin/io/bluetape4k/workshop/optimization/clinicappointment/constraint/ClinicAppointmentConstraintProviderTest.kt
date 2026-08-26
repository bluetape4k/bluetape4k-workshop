package io.bluetape4k.workshop.optimization.clinicappointment.constraint

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentPlanning
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicSchedule
import io.bluetape4k.workshop.optimization.clinicappointment.domain.EquipmentFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ProviderFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.RoomFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.TimeWindow
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ClinicAppointmentConstraintProviderTest {
    private val verifier = ConstraintVerifier.build(
        ClinicAppointmentConstraintProvider(),
        ClinicSchedule::class.java,
        ClinicAppointmentPlanning::class.java,
    )

    private val date = LocalDate.of(2026, 8, 25)
    private val operatingWindow = TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))

    @Test
    fun `provider qualification is a hard constraint`() {
        verifier.verifyThat { _, factory ->
            ClinicAppointmentConstraintProvider.providerQualification(factory)
        }
            .given(appointment(requiredService = "MRI"), ProviderFact("provider-1", setOf("CHECKUP"), listOf(operatingWindow)))
            .penalizesBy(1)
    }

    @Test
    fun `provider availability and clinic operating window are hard constraints`() {
        verifier.verifyThat { _, factory ->
            ClinicAppointmentConstraintProvider.providerAvailability(factory)
        }
            .given(
                appointment(startTime = LocalTime.of(17, 30), durationMinutes = 60),
                ProviderFact("provider-1", setOf("CHECKUP"), listOf(operatingWindow)),
            )
            .penalizesBy(1)

        verifier.verifyThat { _, factory ->
            ClinicAppointmentConstraintProvider.operatingWindow(factory)
        }
            .given(
                appointment(startTime = LocalTime.of(17, 30), durationMinutes = 60),
                ClinicFact("clinic-1", listOf(operatingWindow)),
            )
            .penalizesBy(1)
    }

    @Test
    fun `provider room and equipment overlaps are hard constraints`() {
        val first = appointment(id = "appointment-1", providerId = "provider-1", roomId = "room-1", equipmentId = "equipment-mri")
        val second = appointment(id = "appointment-2", providerId = "provider-1", roomId = "room-1", equipmentId = "equipment-mri", startTime = LocalTime.of(9, 15), requiresEquipment = true)

        verifier.verifyThat { _, factory ->
            ClinicAppointmentConstraintProvider.providerOverlap(factory)
        }.given(first, second).penalizesBy(1)

        verifier.verifyThat { _, factory ->
            ClinicAppointmentConstraintProvider.roomOverlap(factory)
        }.given(first, second).penalizesBy(1)

        verifier.verifyThat { _, factory ->
            ClinicAppointmentConstraintProvider.equipmentOverlap(factory)
        }
            .given(
                first.copyForTest(requiresEquipment = true),
                second,
                EquipmentFact("equipment-mri", setOf("MRI"), listOf(operatingWindow)),
            )
            .penalizesBy(1)
    }

    @Test
    fun `requested provider slot and provider load are soft constraints`() {
        verifier.verifyThat { _, factory ->
            ClinicAppointmentConstraintProvider.requestedProvider(factory)
        }
            .given(appointment(providerId = "provider-2", requestedProviderId = "provider-1"))
            .penalizesBy(1)

        verifier.verifyThat { _, factory ->
            ClinicAppointmentConstraintProvider.requestedSlot(factory)
        }
            .given(appointment(startTime = LocalTime.of(10, 0), requestedStartTime = LocalTime.of(9, 0)))
            .penalizesBy(1)

        verifier.verifyThat { _, factory ->
            ClinicAppointmentConstraintProvider.loadBalance(factory)
        }
            .given(
                appointment(id = "appointment-1", providerId = "provider-1", equipmentId = "equipment-mri"),
                appointment(id = "appointment-2", providerId = "provider-1", equipmentId = "equipment-mri", startTime = LocalTime.of(10, 0)),
            )
            .penalizesBy(1)
    }

    private fun appointment(
        id: String = "appointment-1",
        requiredService: String = "CHECKUP",
        providerId: String? = "provider-1",
        roomId: String? = "room-1",
        equipmentId: String? = null,
        date: LocalDate? = this.date,
        startTime: LocalTime? = LocalTime.of(9, 0),
        durationMinutes: Long = 30,
        requiresEquipment: Boolean = false,
        requestedProviderId: String? = "provider-1",
        requestedDate: LocalDate? = this.date,
        requestedStartTime: LocalTime? = LocalTime.of(9, 0),
    ) = ClinicAppointmentPlanning(
        requestId = id,
        requiredService = requiredService,
        durationMinutes = durationMinutes,
        requestedProviderId = requestedProviderId,
        requestedDate = requestedDate,
        requestedStartTime = requestedStartTime,
        windowStart = LocalTime.of(8, 0),
        windowEnd = LocalTime.of(17, 30),
        requiresEquipment = requiresEquipment,
        providerId = providerId,
        roomId = roomId,
        equipmentId = equipmentId,
        date = date,
        startTime = startTime,
    )

    private fun ClinicAppointmentPlanning.copyForTest(
        requiresEquipment: Boolean,
    ) = ClinicAppointmentPlanning(
        requestId = requestId,
        requiredService = requiredService,
        durationMinutes = durationMinutes,
        requestedProviderId = requestedProviderId,
        requestedDate = requestedDate,
        requestedStartTime = requestedStartTime,
        windowStart = windowStart,
        windowEnd = windowEnd,
        requiresEquipment = requiresEquipment,
        confirmed = confirmed,
        providerId = providerId,
        roomId = roomId,
        equipmentId = equipmentId,
        date = date,
        startTime = startTime,
    )
}
