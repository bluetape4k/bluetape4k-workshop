package io.bluetape4k.workshop.optimization.clinicappointment.web

import io.bluetape4k.workshop.optimization.clinicappointment.domain.ClinicAppointmentProposal
import io.bluetape4k.workshop.optimization.clinicappointment.fixture.ClinicAppointmentFixtures
import io.bluetape4k.workshop.optimization.clinicappointment.solver.ClinicAppointmentSolverPort
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 외부 credential 없이 Solver proposal을 관찰하는 synthetic read-only endpoint다. */
@RestController
@RequestMapping("/api/clinic-appointments")
class ClinicAppointmentDemoController(
    private val solver: ClinicAppointmentSolverPort,
) {
    @GetMapping("/demo")
    fun demo(): ClinicAppointmentDemoResponse = solver.solve(ClinicAppointmentFixtures.snapshot()).toResponse()
}

data class ClinicAppointmentDemoResponse(
    val feasible: Boolean,
    val hardScore: Long,
    val softScore: Long,
    val assignments: List<ClinicAppointmentAssignmentResponse>,
    val unassignedReasons: Map<String, Set<String>>,
)

data class ClinicAppointmentAssignmentResponse(
    val requestId: String,
    val providerId: String?,
    val roomId: String?,
    val equipmentId: String?,
    val date: String?,
    val startTime: String?,
    val endTime: String?,
)

private fun ClinicAppointmentProposal.toResponse(): ClinicAppointmentDemoResponse = ClinicAppointmentDemoResponse(
    feasible = feasible,
    hardScore = hardScore,
    softScore = softScore,
    assignments = assignments.map { assignment ->
        ClinicAppointmentAssignmentResponse(
            requestId = assignment.requestId,
            providerId = assignment.providerId,
            roomId = assignment.roomId,
            equipmentId = assignment.equipmentId,
            date = assignment.date?.toString(),
            startTime = assignment.startTime?.toString(),
            endTime = assignment.endTime?.toString(),
        )
    },
    unassignedReasons = unassignedReasons.mapValues { (_, reasons) -> reasons.map { it.name }.toSet() },
)
