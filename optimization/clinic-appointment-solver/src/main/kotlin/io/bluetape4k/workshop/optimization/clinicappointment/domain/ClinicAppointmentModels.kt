package io.bluetape4k.workshop.optimization.clinicappointment.domain

import java.time.LocalDate
import java.time.LocalTime

/** Synthetic 시간 구간. 시작과 끝은 같은 시각일 수 없으며 끝 시각은 exclusive다. */
data class TimeWindow(
    val start: LocalTime,
    val end: LocalTime,
) {
    init {
        require(start < end) { "time window must have start before end" }
    }

    fun contains(appointmentStart: LocalTime, appointmentEnd: LocalTime): Boolean =
        appointmentStart >= start && appointmentEnd <= end

    fun overlaps(other: TimeWindow): Boolean = start < other.end && other.start < end
}

data class ClinicFact(
    val id: String,
    val operatingWindows: List<TimeWindow>,
)

data class ProviderFact(
    val id: String,
    val services: Set<String>,
    val availability: List<TimeWindow>,
)

data class RoomFact(
    val id: String,
    val services: Set<String>,
    val equipmentIds: Set<String>,
)

data class EquipmentFact(
    val id: String,
    val services: Set<String>,
    val availability: List<TimeWindow>,
)

enum class ConstraintReasonCode {
    MISSING_PROVIDER,
    MISSING_ROOM,
    MISSING_EQUIPMENT,
    MISSING_SLOT,
    UNQUALIFIED_PROVIDER,
    UNAVAILABLE_PROVIDER,
    OUTSIDE_OPERATING_WINDOW,
    OUTSIDE_REQUEST_WINDOW,
    PROVIDER_OVERLAP,
    ROOM_OVERLAP,
    EQUIPMENT_OVERLAP,
}

/** Solver 결과를 저장하지 않고 전달하기 위한 닫힌 assignment 모델. */
data class ClinicAppointmentAssignment(
    val requestId: String,
    val providerId: String?,
    val roomId: String?,
    val equipmentId: String?,
    val date: LocalDate?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
)

data class ClinicAppointmentProposal(
    val assignments: List<ClinicAppointmentAssignment>,
    val hardScore: Long,
    val softScore: Long,
    val feasible: Boolean,
    val unassignedReasons: Map<String, Set<ConstraintReasonCode>>,
) {
    init {
        require(assignments.zipWithNext().all { (left, right) -> left.requestId < right.requestId }) {
            "assignments must be sorted by requestId"
        }
    }
}

/** Solver에 넘기는 synthetic 기준 데이터. 호출자는 내부 planning entity를 소유하지 않는다. */
data class ClinicAppointmentSnapshot(
    val clinic: ClinicFact,
    val providers: List<ProviderFact>,
    val rooms: List<RoomFact>,
    val equipment: List<EquipmentFact>,
    val dates: List<LocalDate>,
    val timeSlots: List<LocalTime>,
    val appointments: List<ClinicAppointmentPlanning>,
)
