package io.bluetape4k.workshop.optimization.clinicappointment.domain

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty
import ai.timefold.solver.core.api.domain.solution.PlanningScore
import ai.timefold.solver.core.api.domain.solution.PlanningSolution
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty
import ai.timefold.solver.core.api.domain.solution.ProblemFactProperty
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider
import ai.timefold.solver.core.api.score.HardSoftScore
import java.time.LocalDate
import java.time.LocalTime

/** Provider·room·equipment 제약과 예약 entity를 묶은 Timefold planning solution. */
@PlanningSolution
class ClinicSchedule(
    @field:ProblemFactProperty
    val clinic: ClinicFact = ClinicFact("", emptyList()),

    @field:ProblemFactCollectionProperty
    val providers: List<ProviderFact> = emptyList(),

    @field:ProblemFactCollectionProperty
    val rooms: List<RoomFact> = emptyList(),

    @field:ProblemFactCollectionProperty
    val equipment: List<EquipmentFact> = emptyList(),

    @field:ValueRangeProvider(id = "providerRange")
    @field:ProblemFactCollectionProperty
    val providerIds: List<String> = emptyList(),

    @field:ValueRangeProvider(id = "roomRange")
    @field:ProblemFactCollectionProperty
    val roomIds: List<String> = emptyList(),

    @field:ValueRangeProvider(id = "equipmentRange")
    @field:ProblemFactCollectionProperty
    val equipmentIds: List<String> = emptyList(),

    @field:ValueRangeProvider(id = "dateRange")
    @field:ProblemFactCollectionProperty
    val dates: List<LocalDate> = emptyList(),

    @field:ValueRangeProvider(id = "timeSlotRange")
    @field:ProblemFactCollectionProperty
    val timeSlots: List<LocalTime> = emptyList(),

    @field:PlanningEntityCollectionProperty
    val appointments: List<ClinicAppointmentPlanning> = emptyList(),

    @field:PlanningScore
    var score: HardSoftScore? = null,
) {
    /** Timefold의 reflection 기반 domain discovery를 위한 no-arg 생성자다. */
    @Suppress("unused")
    constructor() : this(clinic = ClinicFact("", emptyList()))
}
