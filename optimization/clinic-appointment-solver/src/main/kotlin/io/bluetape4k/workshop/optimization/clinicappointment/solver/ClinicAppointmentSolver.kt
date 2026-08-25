package io.bluetape4k.workshop.optimization.clinicappointment.solver

import ai.timefold.solver.core.api.solver.SolverFactory
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicType
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig
import ai.timefold.solver.core.config.localsearch.LocalSearchType
import ai.timefold.solver.core.config.solver.SolverConfig
import ai.timefold.solver.core.config.solver.termination.TerminationConfig
import io.bluetape4k.workshop.optimization.clinicappointment.constraint.ClinicAppointmentConstraintProvider
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentAssignment
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentPlanning
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentProposal
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentSnapshot
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicSchedule
import io.bluetape4k.workshop.optimization.clinicappointment.domain.ConstraintReasonCode
import io.bluetape4k.workshop.optimization.clinicappointment.fixture.ClinicAppointmentFixtures
import org.springframework.stereotype.Service

/** Embedded Timefold Solver를 read-only proposal 계산 경계로 노출한다. */
interface ClinicAppointmentSolverPort {
    fun solve(input: ClinicAppointmentSnapshot): ClinicAppointmentProposal
}

@Service
class ClinicAppointmentSolver(
    private val solverFactory: SolverFactory<ClinicSchedule> = createFactory(),
) : ClinicAppointmentSolverPort {

    override fun solve(input: ClinicAppointmentSnapshot): ClinicAppointmentProposal {
        require(input.appointments.isNotEmpty()) { "at least one appointment is required" }
        require(input.providers.isNotEmpty()) { "at least one provider is required" }
        require(input.rooms.isNotEmpty()) { "at least one room is required" }
        require(input.dates.isNotEmpty()) { "at least one planning date is required" }
        require(input.timeSlots.isNotEmpty()) { "at least one planning time slot is required" }

        val solution = ClinicAppointmentFixtures.solution(input)
        val solved = solverFactory.buildSolver().solve(solution)
        val score = solved.score ?: throw IllegalStateException("Solver returned no score")
        val assignments = solved.appointments
            .sortedBy(ClinicAppointmentPlanning::requestId)
            .map(::toAssignment)

        return ClinicAppointmentProposal(
            assignments = assignments,
            hardScore = score.hardScore,
            softScore = score.softScore,
            feasible = score.isFeasible,
            unassignedReasons = solved.appointments
                .sortedBy(ClinicAppointmentPlanning::requestId)
                .mapNotNull { appointment ->
                    val reasons = reasonsFor(appointment)
                    reasons.takeIf { it.isNotEmpty() }?.let { appointment.requestId to it }
                }
                .toMap(),
        )
    }

    private fun toAssignment(appointment: ClinicAppointmentPlanning): ClinicAppointmentAssignment =
        ClinicAppointmentAssignment(
            requestId = appointment.requestId,
            providerId = appointment.providerId,
            roomId = appointment.roomId,
            equipmentId = appointment.equipmentId,
            date = appointment.date,
            startTime = appointment.startTime,
            endTime = appointment.endTime,
        )

    private fun reasonsFor(appointment: ClinicAppointmentPlanning): Set<ConstraintReasonCode> = buildSet {
        if (appointment.providerId == null) add(ConstraintReasonCode.MISSING_PROVIDER)
        if (appointment.roomId == null) add(ConstraintReasonCode.MISSING_ROOM)
        if (appointment.date == null || appointment.startTime == null) add(ConstraintReasonCode.MISSING_SLOT)
        if (appointment.requiresEquipment && appointment.equipmentId == null) add(ConstraintReasonCode.MISSING_EQUIPMENT)
    }

    companion object {
        fun createFactory(stepCountLimit: Int = 200): SolverFactory<ClinicSchedule> =
            SolverFactory.create(
                SolverConfig()
                    .withSolutionClass(ClinicSchedule::class.java)
                    .withEntityClasses(ClinicAppointmentPlanning::class.java)
                    .withConstraintProviderClass(ClinicAppointmentConstraintProvider::class.java)
                    .withTerminationConfig(
                        TerminationConfig().withStepCountLimit(stepCountLimit),
                    )
                    .withPhases(
                        ConstructionHeuristicPhaseConfig()
                            .withConstructionHeuristicType(ConstructionHeuristicType.FIRST_FIT_DECREASING),
                        LocalSearchPhaseConfig()
                            .withLocalSearchType(LocalSearchType.LATE_ACCEPTANCE),
                    ),
            )
    }
}
