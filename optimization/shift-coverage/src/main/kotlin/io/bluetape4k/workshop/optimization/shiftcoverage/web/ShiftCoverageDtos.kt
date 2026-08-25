package io.bluetape4k.workshop.optimization.shiftcoverage.web

data class ShiftCoveragePlanDto(
    val planId: String,
    val revision: Long,
    val siteId: String,
    val assignments: Int,
    val gaps: Int,
    val coverageMinor: Long,
    val fairnessMinor: Long,
    val reasons: List<String>,
)

/** worker read model은 자기 assignment 식별자만 노출하고 coverage aggregate 지표를 숨깁니다. */
data class ShiftCoverageWorkerPlanDto(
    val planId: String,
    val revision: Long,
    val siteId: String,
    val assignments: Int,
)

data class ShiftCoverageReplanResponse(val accepted: Boolean, val revision: Long, val requestId: String)

data class ShiftCoverageSwapRequestDto(val sourceWorkerId: String, val targetWorkerId: String)

data class ShiftCoverageSwapResponse(val requestId: String, val status: String)

data class ShiftCoverageErrorResponse(
    val code: String,
    val requestId: String,
    val retryable: Boolean,
    val retryAfter: Long? = null,
    val nextAction: String? = null,
)
