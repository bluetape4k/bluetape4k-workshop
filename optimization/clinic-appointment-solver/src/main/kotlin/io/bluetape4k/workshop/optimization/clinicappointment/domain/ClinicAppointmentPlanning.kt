package io.bluetape4k.workshop.optimization.clinicappointment.domain

import ai.timefold.solver.core.api.domain.entity.PlanningEntity
import ai.timefold.solver.core.api.domain.entity.PlanningPin
import ai.timefold.solver.core.api.domain.variable.PlanningVariable
import java.time.LocalDate
import java.time.LocalTime
import java.util.Comparator

/** Timefold가 배치할 synthetic 예약 planning entity. */
@PlanningEntity(comparatorClass = ClinicAppointmentDifficultyComparator::class)
class ClinicAppointmentPlanning(
    val requestId: String = "",
    val requiredService: String = "",
    val durationMinutes: Long = 0L,
    val requestedProviderId: String? = null,
    val requestedDate: LocalDate? = null,
    val requestedStartTime: LocalTime? = null,
    val windowStart: LocalTime = LocalTime.MIN,
    val windowEnd: LocalTime = LocalTime.MAX,
    val requiresEquipment: Boolean = false,

    @field:PlanningPin
    val confirmed: Boolean = false,

    @field:PlanningVariable(allowsUnassigned = true, valueRangeProviderRefs = ["providerRange"])
    var providerId: String? = null,

    @field:PlanningVariable(allowsUnassigned = true, valueRangeProviderRefs = ["roomRange"])
    var roomId: String? = null,

    @field:PlanningVariable(allowsUnassigned = true, valueRangeProviderRefs = ["equipmentRange"])
    var equipmentId: String? = null,

    @field:PlanningVariable(allowsUnassigned = true, valueRangeProviderRefs = ["dateRange"])
    var date: LocalDate? = null,

    @field:PlanningVariable(allowsUnassigned = true, valueRangeProviderRefs = ["timeSlotRange"])
    var startTime: LocalTime? = null,
) {
    /** `@PlanningPin`과 같은 의미를 읽기 쉽게 노출하는 예제용 속성이다. */
    val pinned: Boolean
        get() = confirmed

    val endTime: LocalTime?
        get() = startTime?.plusMinutes(durationMinutes)

    /** Timefold의 reflection 기반 domain discovery를 위한 no-arg 생성자다. */
    @Suppress("unused")
    constructor() : this(requestId = "")
}

/** First Fit Decreasing에서 장비·긴 진료·제한된 날짜를 먼저 배치하는 비교기다. */
class ClinicAppointmentDifficultyComparator : Comparator<ClinicAppointmentPlanning> {
    override fun compare(left: ClinicAppointmentPlanning, right: ClinicAppointmentPlanning): Int =
        compareValuesBy(
            left,
            right,
            { if (it.requiresEquipment) 1 else 0 },
            { it.durationMinutes },
            { if (it.requestedDate != null) 1 else 0 },
            { it.requestId },
        )
}
