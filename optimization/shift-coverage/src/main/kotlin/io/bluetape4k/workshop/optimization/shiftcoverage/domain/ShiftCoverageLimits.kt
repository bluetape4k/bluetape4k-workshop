package io.bluetape4k.workshop.optimization.shiftcoverage.domain

/** Shift Coverage 경계에서 사용하는 입력·실행 상한입니다. */
object ShiftCoverageLimits {
    const val MAX_BODY_BYTES: Int = 256 * 1024
    const val MAX_KEY_BYTES: Int = 200
    const val MAX_STRING_LENGTH: Int = 240
    const val MAX_WORKERS: Int = 100
    const val MAX_SHIFTS: Int = 500
    const val MAX_ASSIGNMENTS: Int = 500
    const val MAX_SKILLS: Int = 20
    const val MAX_AVAILABILITY_WINDOWS: Int = 20
    const val MAX_PREFERENCES: Int = 20
    const val MAX_REASONS: Int = 20
    const val MAX_JSON_DEPTH: Int = 12
    const val MAX_PAGE_SIZE: Int = 100
    const val MAX_CANDIDATES: Int = 50_000
    const val MAX_PLANNER_MILLIS: Long = 5_000L
    const val OPAQUE_TOKEN_LENGTH: Int = 22
    const val RETRY_LIMIT: Int = 5
}
