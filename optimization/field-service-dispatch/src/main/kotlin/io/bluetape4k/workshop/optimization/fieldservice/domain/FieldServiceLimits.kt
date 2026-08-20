package io.bluetape4k.workshop.optimization.fieldservice.domain

import kotlin.math.floor

/** Field Service 예제가 외부 입력과 실행량에 적용하는 단일 상한 표입니다. */
object FieldServiceLimits {
    const val MAX_BODY_BYTES: Int = 256 * 1024
    const val MAX_WORKERS: Int = 100
    const val MAX_VISITS: Int = 500
    const val MAX_ROUTE_STOPS: Int = 500
    const val MAX_COORDINATES: Int = 100
    const val MAX_MATRIX_CELLS: Int = 10_000
    const val MAX_SPARSE_EDGES: Int = 10_000
    const val MAX_SKILLS_PER_WORKER: Int = 20
    const val MAX_AVAILABILITY_WINDOWS_PER_WORKER: Int = 20
    const val MAX_EXPLANATIONS: Int = 20
    const val MAX_EXPLANATION_LENGTH: Int = 240
    const val MAX_JSON_DEPTH: Int = 12
    const val MAX_STRING_LENGTH: Int = 240
    const val MAX_KEY_LENGTH: Int = 200
    const val MAX_PLAN_HISTORY: Int = 100
    const val MAX_PAGE_SIZE: Int = 100
    const val MAX_OUTBOX_BATCH: Int = 10

    fun isFiniteNonNegativeTravelTime(value: Long): Boolean = value >= 0L

    fun isFiniteNonNegativeTravelTime(value: Double): Boolean =
        value.isFinite() && value >= 0.0 && floor(value) == value
}
