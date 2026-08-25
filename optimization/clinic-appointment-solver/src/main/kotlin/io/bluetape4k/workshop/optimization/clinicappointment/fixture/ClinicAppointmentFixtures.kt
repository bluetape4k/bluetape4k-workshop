package io.bluetape4k.workshop.optimization.clinicappointment.fixture

import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentPlanning
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentSnapshot
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicSchedule
import io.bluetape4k.workshop.optimization.clinicappointment.domain.EquipmentFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ProviderFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.RoomFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.TimeWindow
import java.time.LocalDate
import java.time.LocalTime

/** 외부 credential 없이 Solver 동작을 재현하는 고정 synthetic fixture다. */
object ClinicAppointmentFixtures {
    private val dayOne = LocalDate.of(2026, 8, 25)
    private val dayTwo = dayOne.plusDays(1)
    private val operatingWindow = TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))

    fun snapshot(): ClinicAppointmentSnapshot {
        val providers = listOf(
            ProviderFact("provider-1", setOf("CHECKUP", "XRAY"), listOf(operatingWindow)),
            ProviderFact("provider-2", setOf("CHECKUP", "MRI"), listOf(operatingWindow)),
            ProviderFact("provider-3", setOf("MRI", "XRAY"), listOf(operatingWindow)),
        )
        val rooms = listOf(
            RoomFact("room-1", setOf("CHECKUP", "XRAY"), setOf("equipment-xray")),
            RoomFact("room-2", setOf("CHECKUP", "MRI"), setOf("equipment-mri")),
        )
        val equipment = listOf(
            EquipmentFact("equipment-mri", setOf("MRI"), listOf(operatingWindow)),
            EquipmentFact("equipment-xray", setOf("XRAY"), listOf(operatingWindow)),
        )
        val appointments = listOf(
            ClinicAppointmentPlanning(
                requestId = "appointment-1",
                requiredService = "CHECKUP",
                durationMinutes = 30,
                requestedProviderId = "provider-1",
                requestedDate = dayOne,
                requestedStartTime = LocalTime.of(9, 0),
                windowStart = LocalTime.of(8, 0),
                windowEnd = LocalTime.of(17, 30),
            ),
            ClinicAppointmentPlanning(
                requestId = "appointment-2",
                requiredService = "MRI",
                durationMinutes = 60,
                requestedProviderId = "provider-2",
                requestedDate = dayOne,
                requestedStartTime = LocalTime.of(9, 0),
                windowStart = LocalTime.of(8, 0),
                windowEnd = LocalTime.of(17, 0),
                requiresEquipment = true,
            ),
            ClinicAppointmentPlanning(
                requestId = "appointment-3",
                requiredService = "CHECKUP",
                durationMinutes = 30,
                requestedProviderId = "provider-2",
                requestedDate = dayOne,
                requestedStartTime = LocalTime.of(10, 0),
                windowStart = LocalTime.of(8, 0),
                windowEnd = LocalTime.of(17, 30),
                confirmed = true,
                providerId = "provider-2",
                roomId = "room-2",
                date = dayOne,
                startTime = LocalTime.of(10, 0),
            ),
        )
        return ClinicAppointmentSnapshot(
            clinic = ClinicFact("clinic-demo", listOf(operatingWindow)),
            providers = providers,
            rooms = rooms,
            equipment = equipment,
            dates = listOf(dayOne, dayTwo),
            timeSlots = listOf(
                LocalTime.of(8, 0),
                LocalTime.of(8, 30),
                LocalTime.of(9, 0),
                LocalTime.of(9, 30),
                LocalTime.of(10, 0),
                LocalTime.of(10, 30),
                LocalTime.of(11, 0),
                LocalTime.of(11, 30),
                LocalTime.of(13, 0),
                LocalTime.of(13, 30),
                LocalTime.of(14, 0),
            ),
            appointments = appointments,
        )
    }

    fun solution(snapshot: ClinicAppointmentSnapshot = snapshot()): ClinicSchedule = ClinicSchedule(
        clinic = snapshot.clinic,
        providers = snapshot.providers,
        rooms = snapshot.rooms,
        equipment = snapshot.equipment,
        providerIds = snapshot.providers.map { it.id },
        roomIds = snapshot.rooms.map { it.id },
        equipmentIds = snapshot.equipment.map { it.id },
        dates = snapshot.dates,
        timeSlots = snapshot.timeSlots,
        appointments = snapshot.appointments.map(::copyAppointment),
    )

    private fun copyAppointment(source: ClinicAppointmentPlanning): ClinicAppointmentPlanning =
        ClinicAppointmentPlanning(
            requestId = source.requestId,
            requiredService = source.requiredService,
            durationMinutes = source.durationMinutes,
            requestedProviderId = source.requestedProviderId,
            requestedDate = source.requestedDate,
            requestedStartTime = source.requestedStartTime,
            windowStart = source.windowStart,
            windowEnd = source.windowEnd,
            requiresEquipment = source.requiresEquipment,
            confirmed = source.confirmed,
            providerId = source.providerId,
            roomId = source.roomId,
            equipmentId = source.equipmentId,
            date = source.date,
            startTime = source.startTime,
        )
}
