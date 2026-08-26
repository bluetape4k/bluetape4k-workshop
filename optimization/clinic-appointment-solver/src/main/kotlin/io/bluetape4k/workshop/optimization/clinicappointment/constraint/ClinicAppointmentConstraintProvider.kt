package io.bluetape4k.workshop.optimization.clinicappointment.constraint

import ai.timefold.solver.core.api.score.HardSoftScore
import ai.timefold.solver.core.api.score.stream.Constraint
import ai.timefold.solver.core.api.score.stream.ConstraintFactory
import ai.timefold.solver.core.api.score.stream.ConstraintProvider
import ai.timefold.solver.core.api.score.stream.Joiners
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentPlanning
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.EquipmentFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ProviderFact
import io.bluetape4k.workshop.optimization.clinicappointment.domain.RoomFact

/** Clinic appointment planning의 hard/soft score stream을 한 곳에서 정의한다. */
class ClinicAppointmentConstraintProvider : ConstraintProvider {
    override fun defineConstraints(factory: ConstraintFactory): Array<Constraint> = arrayOf(
        assignmentComplete(factory),
        providerQualification(factory),
        providerAvailability(factory),
        operatingWindow(factory),
        requestedWindow(factory),
        roomCompatibility(factory),
        equipmentAvailability(factory),
        providerOverlap(factory),
        roomOverlap(factory),
        equipmentOverlap(factory),
        requestedProvider(factory),
        requestedSlot(factory),
        loadBalance(factory),
    )

    companion object {
        fun assignmentComplete(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { appointment ->
                    appointment.providerId == null || appointment.roomId == null ||
                        appointment.date == null || appointment.startTime == null
                }
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("ASSIGNMENT_COMPLETE")

        fun providerQualification(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { it.providerId != null }
                .ifNotExists(
                    ProviderFact::class.java,
                    Joiners.equal({ appointment -> appointment.providerId }, { provider -> provider.id }),
                    Joiners.filtering { appointment, provider ->
                        provider.services.contains(appointment.requiredService)
                    },
                )
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("PROVIDER_QUALIFICATION")

        fun providerAvailability(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { it.providerId != null && it.startTime != null && it.endTime != null }
                .ifNotExists(
                    ProviderFact::class.java,
                    Joiners.equal({ appointment -> appointment.providerId }, { provider -> provider.id }),
                    Joiners.filtering { appointment, provider ->
                        appointment.startTime?.let { start ->
                            appointment.endTime?.let { end ->
                                provider.availability.any { it.contains(start, end) }
                            }
                        } == true
                    },
                )
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("PROVIDER_AVAILABILITY")

        fun operatingWindow(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { it.date != null && it.startTime != null && it.endTime != null }
                .ifNotExists(
                    ClinicFact::class.java,
                    Joiners.filtering { appointment, clinic ->
                        appointment.startTime?.let { start ->
                            appointment.endTime?.let { end ->
                                clinic.operatingWindows.any { it.contains(start, end) }
                            }
                        } == true
                    },
                )
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("OPERATING_WINDOW")

        fun requestedWindow(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { appointment ->
                    val start = appointment.startTime
                    val end = appointment.endTime
                    start != null && end != null &&
                        (start < appointment.windowStart || end > appointment.windowEnd)
                }
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("REQUESTED_WINDOW")

        fun roomCompatibility(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { it.roomId != null }
                .ifNotExists(
                    RoomFact::class.java,
                    Joiners.equal({ appointment -> appointment.roomId }, { room -> room.id }),
                    Joiners.filtering { appointment, room ->
                        room.services.contains(appointment.requiredService)
                    },
                )
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("ROOM_COMPATIBILITY")

        fun equipmentAvailability(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { it.requiresEquipment && it.equipmentId != null && it.startTime != null && it.endTime != null }
                .ifNotExists(
                    EquipmentFact::class.java,
                    Joiners.equal({ appointment -> appointment.equipmentId }, { equipment -> equipment.id }),
                    Joiners.filtering { appointment, equipment ->
                        equipment.services.contains(appointment.requiredService) &&
                            appointment.startTime?.let { start ->
                                appointment.endTime?.let { end ->
                                    equipment.availability.any { it.contains(start, end) }
                                }
                            } == true
                    },
                )
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("EQUIPMENT_AVAILABILITY")

        fun providerOverlap(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .join(
                    ClinicAppointmentPlanning::class.java,
                    Joiners.equal(ClinicAppointmentPlanning::providerId),
                    Joiners.equal(ClinicAppointmentPlanning::date),
                    Joiners.lessThan(ClinicAppointmentPlanning::requestId),
                )
                .filter { left, right -> overlaps(left, right) }
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("PROVIDER_OVERLAP")

        fun roomOverlap(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .join(
                    ClinicAppointmentPlanning::class.java,
                    Joiners.equal(ClinicAppointmentPlanning::roomId),
                    Joiners.equal(ClinicAppointmentPlanning::date),
                    Joiners.lessThan(ClinicAppointmentPlanning::requestId),
                )
                .filter { left, right -> overlaps(left, right) }
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("ROOM_OVERLAP")

        fun equipmentOverlap(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { it.requiresEquipment && it.equipmentId != null }
                .join(
                    ClinicAppointmentPlanning::class.java,
                    Joiners.equal(ClinicAppointmentPlanning::equipmentId),
                    Joiners.equal(ClinicAppointmentPlanning::date),
                    Joiners.lessThan(ClinicAppointmentPlanning::requestId),
                )
                .filter { left, right -> right.requiresEquipment && overlaps(left, right) }
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("EQUIPMENT_OVERLAP")

        fun requestedProvider(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { it.requestedProviderId != null && it.providerId != it.requestedProviderId }
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("REQUESTED_PROVIDER")

        fun requestedSlot(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { it.requestedStartTime != null && it.startTime != it.requestedStartTime }
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("REQUESTED_SLOT")

        fun loadBalance(factory: ConstraintFactory): Constraint =
            factory.forEachIncludingUnassigned(ClinicAppointmentPlanning::class.java)
                .filter { it.providerId != null && it.date != null }
                .join(
                    ClinicAppointmentPlanning::class.java,
                    Joiners.equal(ClinicAppointmentPlanning::providerId),
                    Joiners.equal(ClinicAppointmentPlanning::date),
                    Joiners.lessThan(ClinicAppointmentPlanning::requestId),
                )
                .penalize(HardSoftScore.ofSoft(2))
                .asConstraint("LOAD_BALANCE")

        private fun overlaps(left: ClinicAppointmentPlanning, right: ClinicAppointmentPlanning): Boolean {
            val leftStart = left.startTime ?: return false
            val leftEnd = left.endTime ?: return false
            val rightStart = right.startTime ?: return false
            val rightEnd = right.endTime ?: return false
            return leftStart < rightEnd && rightStart < leftEnd
        }
    }
}
